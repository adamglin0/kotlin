/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.tasks

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.*
import org.gradle.work.ChangeType
import org.gradle.work.Incremental
import org.gradle.work.InputChanges
import org.gradle.work.NormalizeLineEndings
import org.gradle.workers.WorkerExecutor
import org.jetbrains.kotlin.build.report.metrics.BuildPerformanceMetric
import org.jetbrains.kotlin.build.report.metrics.BuildTime
import org.jetbrains.kotlin.build.report.metrics.measure
import org.jetbrains.kotlin.cli.common.CompilerSystemProperties
import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.compilerRunner.CompilerExecutionSettings
import org.jetbrains.kotlin.compilerRunner.GradleCompilerRunner
import org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers
import org.jetbrains.kotlin.compilerRunner.UsesCompilerSystemPropertiesService
import org.jetbrains.kotlin.daemon.common.MultiModuleICSettings
import org.jetbrains.kotlin.gradle.incremental.UsesIncrementalModuleInfoBuildService
import org.jetbrains.kotlin.gradle.internal.AbstractKotlinCompileArgumentsContributor
import org.jetbrains.kotlin.gradle.internal.compilerArgumentsConfigurationFlags
import org.jetbrains.kotlin.gradle.internal.prepareCompilerArguments
import org.jetbrains.kotlin.gradle.internal.tasks.allOutputFiles
import org.jetbrains.kotlin.gradle.logging.GradleKotlinLogger
import org.jetbrains.kotlin.gradle.logging.kotlinDebug
import org.jetbrains.kotlin.gradle.plugin.UsesVariantImplementationFactories
import org.jetbrains.kotlin.gradle.plugin.statistics.KotlinBuildStatsService
import org.jetbrains.kotlin.gradle.report.*
import org.jetbrains.kotlin.gradle.report.UsesBuildMetricsService
import org.jetbrains.kotlin.gradle.report.UsesBuildReportsService
import org.jetbrains.kotlin.gradle.utils.*
import org.jetbrains.kotlin.gradle.utils.newInstance
import org.jetbrains.kotlin.gradle.utils.property
import org.jetbrains.kotlin.gradle.utils.propertyWithConvention
import org.jetbrains.kotlin.gradle.utils.propertyWithNewInstance
import org.jetbrains.kotlin.incremental.ChangedFiles
import org.jetbrains.kotlin.incremental.IncrementalCompilerRunner
import org.jetbrains.kotlin.statistics.metrics.BooleanMetrics
import java.io.File
import javax.inject.Inject
import org.jetbrains.kotlin.gradle.tasks.cleanOutputsAndLocalState as cleanOutputsAndLocalStateUtil

abstract class AbstractKotlinCompile<T : CommonCompilerArguments> @Inject constructor(
    objectFactory: ObjectFactory,
    workerExecutor: WorkerExecutor
) : AbstractKotlinCompileTool<T>(objectFactory),
    CompileUsingKotlinDaemonWithNormalization,
    UsesBuildMetricsService,
    UsesBuildReportsService,
    UsesIncrementalModuleInfoBuildService,
    UsesCompilerSystemPropertiesService,
    UsesVariantImplementationFactories,
    BaseKotlinCompile {

    init {
        cacheOnlyIfEnabledForKotlin()
    }

    private val layout = project.layout

    @get:Inject
    internal abstract val fileSystemOperations: FileSystemOperations

    // avoid creating directory in getter: this can lead to failure in parallel build
    @get:OutputDirectory
    internal open val taskBuildCacheableOutputDirectory: DirectoryProperty = objectFactory.directoryProperty()

    // avoid creating directory in getter: this can lead to failure in parallel build
    @get:LocalState
    internal abstract val taskBuildLocalStateDirectory: DirectoryProperty

    @get:Internal
    internal val buildHistoryFile
        get() = taskBuildLocalStateDirectory.file("build-history.bin")

    // indicates that task should compile kotlin incrementally if possible
    // it's not possible when IncrementalTaskInputs#isIncremental returns false (i.e first build)
    // todo: deprecate and remove (we may need to design api for configuring IC)
    // don't rely on it to check if IC is enabled, use isIncrementalCompilationEnabled instead
    @get:Internal
    var incremental: Boolean = false
        set(value) {
            field = value
            logger.kotlinDebug { "Set $this.incremental=$value" }
        }

    @Input
    internal open fun isIncrementalCompilationEnabled(): Boolean =
        incremental

    @Deprecated("Scheduled for removal with Kotlin 1.9", ReplaceWith("moduleName"))
    @get:Input
    abstract val ownModuleName: Property<String>

    @get:Internal
    val startParameters = BuildReportsService.getStartParameters(project)

    @get:Internal
    internal abstract val suppressKotlinOptionsFreeArgsModificationWarning: Property<Boolean>

    internal fun reportingSettings() = buildReportsService.orNull?.parameters?.reportingSettings?.orNull ?: ReportingSettings()

    @get:Internal
    protected val multiModuleICSettings: MultiModuleICSettings
        get() = MultiModuleICSettings(buildHistoryFile.get().asFile, useModuleDetection.get())

    /**
     * Plugin Data provided by [KpmCompilerPlugin]
     */
    @get:Optional
    @get:Nested
    // TODO: replace with objects.property and introduce task configurator
    internal var kotlinPluginData: Provider<KotlinCompilerPluginData>? = null

    @get:Internal
    internal val javaOutputDir: DirectoryProperty = objectFactory.directoryProperty()

    @get:InputFiles
    @get:IgnoreEmptyDirectories
    @get:Incremental
    @get:NormalizeLineEndings
    @get:PathSensitive(PathSensitivity.RELATIVE)
    internal val commonSourceSet: ConfigurableFileCollection = objectFactory.fileCollection()

    @get:Internal
    val abiSnapshotFile
        get() = taskBuildCacheableOutputDirectory.file(IncrementalCompilerRunner.ABI_SNAPSHOT_FILE_NAME)

    @get:Input
    val abiSnapshotRelativePath: Property<String> = objectFactory.property(String::class.java).value(
        //TODO update to support any jar changes
        "$name/${IncrementalCompilerRunner.ABI_SNAPSHOT_FILE_NAME}"
    )

    @get:Internal
    internal val friendSourceSets = objectFactory.listProperty(String::class.java)

    private val kotlinLogger by lazy { GradleKotlinLogger(logger) }

    @get:Internal
    protected val gradleCompileTaskProvider: Provider<GradleCompileTaskProvider> = objectFactory
        .property(
            objectFactory.newInstance<GradleCompileTaskProvider>(project.gradle, this, project, incrementalModuleInfoProvider)
        )

    @get:Internal
    internal open val defaultKotlinJavaToolchain: Provider<DefaultKotlinJavaToolchain> = objectFactory
        .propertyWithNewInstance({ null })

    @get:Internal
    internal val compilerRunner: Provider<GradleCompilerRunner> =
        objectFactory.propertyWithConvention(
            gradleCompileTaskProvider.flatMap { taskProvider ->
                compilerExecutionStrategy
                    .zip(metrics) { executionStrategy, metrics ->
                        metrics to executionStrategy
                    }
                    .flatMap { params ->
                        defaultKotlinJavaToolchain
                            .map {
                                val toolsJar = it.currentJvmJdkToolsJar.orNull
                                GradleCompilerRunnerWithWorkers(
                                    taskProvider,
                                    toolsJar,
                                    CompilerExecutionSettings(
                                        normalizedKotlinDaemonJvmArguments.orNull,
                                        params.second,
                                        useDaemonFallbackStrategy.get()
                                    ),
                                    params.first,
                                    workerExecutor
                                )
                            }
                    }
            }
        )

    @get:Internal
    internal abstract val preciseCompilationResultsBackup: Property<Boolean>

    @get:Internal
    internal abstract val keepIncrementalCompilationCachesInMemory: Property<Boolean>

    /** Task outputs that we don't want to include in [TaskOutputsBackup] (see [TaskOutputsBackup.outputsToRestore] for more info). */
    @get:Internal
    internal abstract val taskOutputsBackupExcludes: SetProperty<File>

    @TaskAction
    fun execute(inputChanges: InputChanges) {
        val buildMetrics = metrics.get()
        buildMetrics.addTimeMetric(BuildPerformanceMetric.START_TASK_ACTION_EXECUTION)
        buildMetrics.measure(BuildTime.OUT_OF_WORKER_TASK_ACTION) {
            KotlinBuildStatsService.applyIfInitialised {
                if (name.contains("Test"))
                    it.report(BooleanMetrics.TESTS_EXECUTED, true)
                else
                    it.report(BooleanMetrics.COMPILATION_STARTED, true)
            }
            validateCompilerClasspath()
            systemPropertiesService.get().startIntercept()
            CompilerSystemProperties.KOTLIN_COMPILER_ENVIRONMENT_KEEPALIVE_PROPERTY.value = "true"

            // If task throws exception, but its outputs are changed during execution,
            // then Gradle forces next build to be non-incremental (see Gradle's DefaultTaskArtifactStateRepository#persistNewOutputs)
            // To prevent this, we backup outputs before incremental build and restore when exception is thrown
            val outputsBackup: TaskOutputsBackup? =
                if (isIncrementalCompilationEnabled() && inputChanges.isIncremental)
                    buildMetrics.measure(BuildTime.BACKUP_OUTPUT) {
                        TaskOutputsBackup(
                            fileSystemOperations,
                            layout.buildDirectory,
                            layout.buildDirectory.dir("snapshot/kotlin/$name"),
                            outputsToRestore = allOutputFiles() - taskOutputsBackupExcludes.get(),
                            logger
                        ).also {
                            it.createSnapshot()
                        }
                    }
                else null

            if (!isIncrementalCompilationEnabled()) {
                cleanOutputsAndLocalState("IC is disabled")
            } else if (!inputChanges.isIncremental) {
                cleanOutputsAndLocalState("Task cannot run incrementally")
            }

            executeImpl(inputChanges, outputsBackup)
        }

        buildMetricsService.orNull?.also { it.addTask(path, this.javaClass, buildMetrics) }
    }

    protected open fun cleanOutputsAndLocalState(reason: String?) {
        cleanOutputsAndLocalStateUtil(reason)
    }

    protected open fun skipCondition(): Boolean = sources.isEmpty

    @get:Internal
    protected open val incrementalProps: List<FileCollection>
        get() = listOfNotNull(
            sources,
            libraries,
            commonSourceSet
        )

    private fun executeImpl(
        inputChanges: InputChanges,
        taskOutputsBackup: TaskOutputsBackup?
    ) {
        val allKotlinSources = sources.asFileTree.files

        logger.kotlinDebug { "All kotlin sources: ${allKotlinSources.pathsAsStringRelativeTo(gradleCompileTaskProvider.get().projectDir.get())}" }

        if (!inputChanges.isIncremental && skipCondition()) {
            // Skip running only if non-incremental run. Otherwise, we may need to do some cleanup.
            logger.kotlinDebug { "No Kotlin files found, skipping Kotlin compiler task" }
            return
        }

        val args = prepareCompilerArguments()
        taskBuildCacheableOutputDirectory.get().asFile.mkdirs()
        taskBuildLocalStateDirectory.get().asFile.mkdirs()
        callCompilerAsync(
            args,
            allKotlinSources,
            inputChanges,
            taskOutputsBackup
        )
    }

    protected fun getChangedFiles(
        inputChanges: InputChanges,
        incrementalProps: List<FileCollection>
    ) = if (!inputChanges.isIncremental) {
        ChangedFiles.Unknown()
    } else {
        incrementalProps
            .fold(mutableListOf<File>() to mutableListOf<File>()) { (modified, removed), prop ->
                inputChanges.getFileChanges(prop).forEach {
                    when (it.changeType) {
                        ChangeType.ADDED, ChangeType.MODIFIED -> modified.add(it.file)
                        ChangeType.REMOVED -> removed.add(it.file)
                        else -> Unit
                    }
                }
                modified to removed
            }
            .run {
                ChangedFiles.Known(first, second)
            }
    }

    /**
     * Compiler might be executed asynchronously. Do not do anything requiring end of compilation after this function is called.
     * @see [GradleKotlinCompilerWork]
     */
    internal abstract fun callCompilerAsync(
        args: T,
        kotlinSources: Set<File>,
        inputChanges: InputChanges,
        taskOutputsBackup: TaskOutputsBackup?
    )

    @get:Internal
    internal val abstractKotlinCompileArgumentsContributor by lazy {
        AbstractKotlinCompileArgumentsContributor(
            KotlinCompileArgumentsProvider(this)
        )
    }

    override fun setupCompilerArgs(args: T, defaultsOnly: Boolean, ignoreClasspathResolutionErrors: Boolean) {
        abstractKotlinCompileArgumentsContributor.contributeArguments(
            args,
            compilerArgumentsConfigurationFlags(defaultsOnly, ignoreClasspathResolutionErrors)
        )
        if (reportingSettings().buildReportMode == BuildReportMode.VERBOSE) {
            args.reportPerf = true
        }
    }
}
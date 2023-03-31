/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:JvmName("ArgumentsToStrings")

package org.jetbrains.kotlin.compilerRunner

import org.jetbrains.kotlin.cli.common.arguments.Argument
import org.jetbrains.kotlin.cli.common.arguments.CommonToolArguments
import org.jetbrains.kotlin.cli.common.arguments.isAdvanced
import org.jetbrains.kotlin.cli.common.arguments.resolvedDelimiter
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties

@Suppress("UNCHECKED_CAST")
@JvmOverloads
fun CommonToolArguments.toArgumentStrings(useShortNames: Boolean = false): List<String> {
    return toArgumentStrings(
        this, this::class as KClass<CommonToolArguments>, useShortNames = useShortNames
    )
}

@PublishedApi
internal fun <T : CommonToolArguments> toArgumentStrings(thisArguments: T, type: KClass<T>, useShortNames: Boolean): List<String> {
    val defaultArguments = type.newArgumentsInstance()
    val result = mutableListOf<String>()
    type.memberProperties.forEach { property ->
        val argumentAnnotation = property.findAnnotation<Argument>() ?: return@forEach
        val rawPropertyValue = property.get(thisArguments)
        val rawDefaultValue = property.get(defaultArguments)

        /* Default value can be omitted */
        if (rawPropertyValue == rawDefaultValue) {
            return@forEach
        }

        val argumentStringValues = when {
            property.returnType.classifier == Boolean::class -> listOf(rawPropertyValue?.toString() ?: false.toString())

            (property.returnType.classifier as? KClass<*>)?.java?.isArray == true ->
                getArgumentStringValue(argumentAnnotation, rawPropertyValue as Array<*>?)

            property.returnType.classifier == List::class ->
                getArgumentStringValue(argumentAnnotation, (rawPropertyValue as List<*>?)?.toTypedArray())

            else -> listOf(rawPropertyValue.toString())
        }

        val argumentName = if (useShortNames && argumentAnnotation.shortName.isNotEmpty()) argumentAnnotation.shortName
        else argumentAnnotation.value

        argumentStringValues.forEach { argumentStringValue ->

            when {
                /* We can just enable the flag by passing the argument name like -myFlag: Value not required */
                rawPropertyValue is Boolean && rawPropertyValue -> {
                    result.add(argumentName)
                }

                /* Advanced (e.g. -X arguments) or boolean properties need to be passed using the '=' */
                argumentAnnotation.isAdvanced || property.returnType.classifier == Boolean::class -> {
                    result.add("$argumentName=$argumentStringValue")
                }
                else -> {
                    result.add(argumentName)
                    result.add(argumentStringValue)
                }
            }
        }
    }

    result.addAll(thisArguments.freeArgs)
    result.addAll(thisArguments.internalArguments.map { it.stringRepresentation })
    return result
}

private fun getArgumentStringValue(argumentAnnotation: Argument, values: Array<*>?): List<String> {
    if (values.isNullOrEmpty()) return emptyList()
    val delimiter = argumentAnnotation.resolvedDelimiter
    return if (delimiter.isNullOrEmpty()) values.map { it.toString() }
    else listOf(values.joinToString(delimiter))
}

private fun <T : CommonToolArguments> KClass<T>.newArgumentsInstance(): T {
    val argumentConstructor = constructors.find { it.parameters.isEmpty() } ?: throw IllegalArgumentException(
        "$qualifiedName has no empty constructor"
    )
    return argumentConstructor.call()
}
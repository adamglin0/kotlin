// TARGET_BACKEND: WASM

// RUN_THIRD_PARTY_OPTIMIZER
// WASM_DCE_EXPECTED_OUTPUT_SIZE: wasm 14_072
// WASM_DCE_EXPECTED_OUTPUT_SIZE:  mjs  5_109
// WASM_OPT_EXPECTED_OUTPUT_SIZE:       4_108

fun box() = "OK"
@file:OptIn(ExperimentalWasmJsInterop::class)
@file:JsModule("@embedpdf/pdfium")

package com.moneymanager.pdf

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsModule
import kotlin.js.Promise

/** Loads pdfium's WebAssembly build (`pdfium.wasm`, beside the module) and wraps its functions. */
internal external fun init(moduleOverrides: JsAny): Promise<JsAny>

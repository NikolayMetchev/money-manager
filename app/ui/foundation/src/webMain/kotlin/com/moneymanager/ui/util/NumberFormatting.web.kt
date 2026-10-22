@file:OptIn(ExperimentalWasmJsInterop::class)

package com.moneymanager.ui.util

import kotlin.js.ExperimentalWasmJsInterop

// Through BigInt, so a Long never crosses into JavaScript as a number.
actual fun formatGroupedNumber(value: Long): String = groupDecimal(value.toString())

private fun groupDecimal(
    @Suppress("UnusedParameter") decimal: String,
): String = js("BigInt(decimal).toLocaleString()")

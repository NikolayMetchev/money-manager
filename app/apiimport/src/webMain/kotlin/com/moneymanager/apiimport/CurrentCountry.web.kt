@file:OptIn(ExperimentalWasmJsInterop::class)

package com.moneymanager.apiimport

import kotlin.js.ExperimentalWasmJsInterop

actual fun currentCountryCode(): String? = countryFromLocaleTag(browserLanguage())

// The browser's preferred language, a BCP 47 tag (`en-GB`); Node has one too, and other hosts may not.
private fun browserLanguage(): String? = js("(globalThis.navigator && globalThis.navigator.language) || null")

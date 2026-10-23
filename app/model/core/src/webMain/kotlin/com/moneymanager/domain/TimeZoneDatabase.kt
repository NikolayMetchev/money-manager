package com.moneymanager.domain

import kotlin.js.ExperimentalWasmJsInterop

/**
 * kotlinx-datetime finds time zones through js-joda, which comes without a zone database in the browser. Importing
 * `@js-joda/timezone` registers one with it, so every program that links the domain model can name a zone.
 */
@OptIn(ExperimentalWasmJsInterop::class)
@JsModule("@js-joda/timezone")
private external object JsJodaTimeZoneModule : JsAny

@Suppress("UnusedPrivateProperty")
private val timeZoneDatabase = JsJodaTimeZoneModule

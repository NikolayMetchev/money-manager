package com.moneymanager.ui.test

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi

/**
 * Runs a Compose UI test with the main dispatcher as the effect context. Android's test library replaced
 * `runComposeUiTest`'s individual parameters with a `ComposeUiTestConfig` that the multiplatform one doesn't have
 * yet, so each platform calls its own.
 */
@ExperimentalTestApi
expect fun runMoneyManagerComposeUiTest(block: ComposeUiTest.() -> Unit)

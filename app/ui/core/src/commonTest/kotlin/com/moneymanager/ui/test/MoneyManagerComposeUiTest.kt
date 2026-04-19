package com.moneymanager.ui.test

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.Dispatchers

@ExperimentalTestApi
fun runMoneyManagerComposeUiTest(block: ComposeUiTest.() -> Unit) {
    try {
        runComposeUiTest(
            effectContext = Dispatchers.Main,
            block = block,
        )
    } catch (expected: IllegalArgumentException) {
        if (expected.message?.contains("effectContext isn't supported") != true) {
            throw expected
        }
        runComposeUiTest(block = block)
    }
}

package com.moneymanager.ui.test

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.Dispatchers

@ExperimentalTestApi
fun runMoneyManagerComposeUiTest(block: ComposeUiTest.() -> Unit) {
    runComposeUiTest(
        effectContext = Dispatchers.Main,
    ) {
        block()
    }
}

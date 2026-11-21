@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, ExperimentalMaterial3Api::class)

package com.moneymanager.ui.util

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Text
import androidx.compose.ui.test.onNodeWithText
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import com.moneymanager.ui.util.ExposedDropdownMenu as SharedExposedDropdownMenu

/**
 * Calls the shared wrapper by an alias: a plain call inside the box scope would reach the scope's own member
 * wherever it exists, and never the wrapper that Android relies on.
 */
class ExposedDropdownMenuTest {
    @Test
    fun theWrapperShowsItsItems() =
        runMoneyManagerComposeUiTest {
            var dismissed = 0
            setContent {
                ExposedDropdownMenuBox(expanded = true, onExpandedChange = {}) {
                    SharedExposedDropdownMenu(expanded = true, onDismissRequest = { dismissed++ }) {
                        DropdownMenuItem(text = { Text("First choice") }, onClick = {})
                    }
                }
            }

            onNodeWithText("First choice").assertExists()
            assertEquals(0, dismissed)
        }
}

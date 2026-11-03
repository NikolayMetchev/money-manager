@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.screens.csvstrategy.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import com.moneymanager.builtin.BuiltInCsvStrategies
import com.moneymanager.domain.model.rules.SettlementWindow
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class AdvancedTabTest {
    private val strategy = BuiltInCsvStrategies.buildBinanceCsvStrategy(Instant.fromEpochMilliseconds(1_000))

    private fun androidx.compose.ui.test.ComposeUiTest.showAdvancedTab(state: CsvStrategyEditorState) {
        setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AdvancedTab(
                        state = state,
                        csvColumns = emptyList(),
                        firstRow = null,
                        enabled = true,
                        existingAttributeTypes = emptyList(),
                        isEditMode = true,
                        accountMappings = emptyList(),
                        accounts = emptyList(),
                        onEditAccountMapping = {},
                        onDeleteAccountMapping = {},
                        onAddAccountMapping = {},
                    )
                }
            }
        }
    }

    @Test
    fun `the duplicate tolerance field edits the strategy and a blank field clears it`() {
        runMoneyManagerComposeUiTest {
            val state = CsvStrategyEditorState(strategy, emptySet())
            assertEquals(SettlementWindow.Elapsed(0), state.duplicateDateTolerance)
            showAdvancedTab(state)

            onNodeWithText("Duplicate date tolerance (seconds)").performTextReplacement("90")
            waitForIdle()
            assertEquals(SettlementWindow.Elapsed(90), state.duplicateDateTolerance)

            onNodeWithText("Duplicate date tolerance (seconds)").performTextReplacement("")
            waitForIdle()
            assertNull(state.duplicateDateTolerance)

            onNodeWithText("Duplicate date tolerance (seconds)").performTextReplacement("-5")
            waitForIdle()
            assertNull(state.duplicateDateTolerance)
        }
    }
}

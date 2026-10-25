@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.screens.csvstrategy.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.moneymanager.builtin.BuiltInCsvStrategies
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csvstrategy.PdfLineConfig
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

class PdfLinesRulesTabTest {
    private val strategy = BuiltInCsvStrategies.buildAmexPdfStrategy(Instant.fromEpochMilliseconds(1_000))
    private val columnNames =
        (PdfLineConfig.BASE_COLUMNS + listOf("statementDate", "date", "description", "amount", "credit")).toSet()

    @Test
    fun `saving a PDF strategy from the editor keeps its line rules`() {
        val state = CsvStrategyEditorState(strategy, columnNames)
        val rebuilt = buildStrategyFromEditorState(state, strategy.id, strategy.createdAt, strategy.updatedAt)
        assertEquals(strategy.config.pdfLines, rebuilt.config.pdfLines)
        assertEquals(true, state.isValid)
    }

    @Test
    fun `a CSV strategy has no line rules`() {
        val csv = BuiltInCsvStrategies.buildAmexPdfStrategy(strategy.createdAt).let { it.copy(config = it.config.copy(pdfLines = null)) }
        val rebuilt = buildStrategyFromEditorState(CsvStrategyEditorState(csv, columnNames), csv.id, csv.createdAt, csv.updatedAt)
        assertNull(rebuilt.config.pdfLines)
    }

    @Test
    fun `the rules tab shows the line rules above the row rules`() {
        runMoneyManagerComposeUiTest {
            val state = CsvStrategyEditorState(strategy, columnNames)
            val columns = columnNames.mapIndexed { index, name -> CsvColumn(CsvColumnId(Uuid.random()), index, name) }
            setContent {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        RulesTab(state = state, csvColumns = columns, firstRow = null, enabled = true)
                    }
                }
            }
            onNodeWithText("PDF lines").assertExists()
            onNodeWithText("Rows").assertExists()
            val documentCapture = onAllNodesWithText("capture statementDate", substring = true)
            documentCapture[0].assertExists().performClick()
            waitForIdle()
            onNodeWithText("Holds a value for every row").assertExists()
        }
    }
}

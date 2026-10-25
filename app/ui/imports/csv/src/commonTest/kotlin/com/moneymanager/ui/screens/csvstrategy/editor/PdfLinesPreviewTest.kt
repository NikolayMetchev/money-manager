@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.screens.csvstrategy.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithText
import com.moneymanager.domain.model.csv.PdfTextLine
import com.moneymanager.domain.model.csvstrategy.PdfLineConfig
import com.moneymanager.domain.model.rules.CaptureTarget
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.StrategyRule
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PdfLinesPreviewTest {
    private val lines =
        listOf(
            "Statement Date 19/01/13",
            "Dec 20   TESCO STORES   12.50",
            "Jan 2   PAYMENT RECEIVED - THANK YOU   2,609.36",
        ).mapIndexed { index, text -> PdfTextLine(page = 1, line = index + 1, text = text) }

    private fun capture(pattern: String) =
        StrategyRule(actions = listOf(RuleAction.Capture(PdfLineConfig.TEXT, pattern, CaptureTarget.NEW_ROW)))

    private val rowRule = capture("""^(?<date>[A-Z][a-z]{2} \d{1,2}) {2,}(?<description>.+?) {2,}(?<amount>[\d,]+\.\d\d)$""")

    @Test
    fun `the line rules assemble the columns and rows the editor reads`() {
        val preview = PdfLinesPreviewer(lines).preview(listOf(rowRule))

        assertEquals(PdfLineConfig.BASE_COLUMNS + listOf("date", "description", "amount"), preview.columns.map { it.originalName })
        assertEquals(listOf("Dec 20", "Jan 2"), preview.rows.map { it.values[3] })
        assertEquals(3, preview.lineCount)
        assertNull(preview.error)
    }

    @Test
    fun `with no line rules each line is a row`() {
        val preview = PdfLinesPreviewer(lines).preview(emptyList())

        assertEquals(PdfLineConfig.BASE_COLUMNS, preview.columns.map { it.originalName })
        assertEquals(0, preview.rows.size)
    }

    @Test
    fun `a pattern still being typed keeps the last rows and says why`() {
        val previewer = PdfLinesPreviewer(lines)
        val good = previewer.preview(listOf(rowRule))

        val typing = previewer.preview(listOf(capture("""^(?<date""")))

        assertNotNull(typing.error)
        assertEquals(good.columns, typing.columns)
        assertEquals(good.rows, typing.rows)
    }

    @Test
    fun `a column keeps its id while the rules change`() {
        val previewer = PdfLinesPreviewer(lines)
        val before = previewer.preview(listOf(rowRule)).columns.first { it.originalName == "amount" }
        val after = previewer.preview(listOf(capture("""(?<amount>[\d,]+\.\d\d)$"""))).columns.first { it.originalName == "amount" }

        assertEquals(before.id, after.id)
    }

    @Test
    fun `a new strategy over a PDF import is a PDF strategy over the line columns`() {
        val state = CsvStrategyEditorState(strategy = null, availableColumnNames = PdfLineConfig.BASE_COLUMNS.toSet(), pdf = true)

        assertEquals(emptyList(), state.pdfLineRules)
        assertEquals(PdfLineConfig.BASE_COLUMNS.toSet(), state.identificationColumns)
    }

    @Test
    fun `the rules tab previews the rows the line rules assemble`() {
        runMoneyManagerComposeUiTest {
            val state = CsvStrategyEditorState(strategy = null, availableColumnNames = PdfLineConfig.BASE_COLUMNS.toSet(), pdf = true)
            state.pdfLineRules = listOf(rowRule)
            val preview = PdfLinesPreviewer(lines).preview(listOf(rowRule))
            setContent {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        RulesTab(
                            state = state,
                            csvColumns = preview.columns,
                            firstRow = preview.rows.first(),
                            enabled = true,
                            pdfPreview = preview,
                        )
                    }
                }
            }

            onNodeWithText("2 rows from 3 lines").assertExists()
            onNodeWithText("TESCO STORES").assertExists()
            onNodeWithText("2,609.36").assertExists()
        }
    }
}

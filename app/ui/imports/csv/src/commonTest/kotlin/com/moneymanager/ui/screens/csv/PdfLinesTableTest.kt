@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.screens.csv

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import com.moneymanager.domain.model.csv.PdfTextLine
import com.moneymanager.ui.components.csv.PdfLinesTable
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlin.test.Test

class PdfLinesTableTest {
    @Test
    fun showsEachLineWithItsPageAndNumber() {
        runMoneyManagerComposeUiTest {
            setContent {
                MaterialTheme {
                    PdfLinesTable(
                        lines =
                            listOf(
                                PdfTextLine(page = 1, line = 1, text = "Statement Date 19/01/13"),
                                PdfTextLine(page = 2, line = 7, text = "Jan 2   PAYMENT RECEIVED - THANK YOU   2,609.36"),
                            ),
                    )
                }
            }

            onNodeWithText("Statement Date 19/01/13").assertIsDisplayed()
            // Runs of spaces keep columns apart, so the text is shown exactly as line rules read it.
            onNodeWithText("Jan 2   PAYMENT RECEIVED - THANK YOU   2,609.36").assertIsDisplayed()
            onNodeWithText("7").assertIsDisplayed()
        }
    }
}

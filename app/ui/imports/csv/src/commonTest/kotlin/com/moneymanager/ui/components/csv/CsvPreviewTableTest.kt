@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.components.csv

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class CsvPreviewTableTest {
    @Test
    fun scrollToRow_putsTheRowInTheMiddleOfTheScreen() {
        runMoneyManagerComposeUiTest {
            setContent {
                MaterialTheme {
                    CsvPreviewTable(
                        columns = listOf(CsvColumn(id = CsvColumnId(Uuid.random()), columnIndex = 0, originalName = "Name")),
                        rows = (1L..200L).map { CsvRow(rowIndex = it, values = listOf("row $it")) },
                        scrollToRowIndex = 100,
                    )
                }
            }
            waitForIdle()

            val row = onNodeWithText("row 100").fetchSemanticsNode().boundsInRoot
            val screen = onRoot().fetchSemanticsNode().boundsInRoot
            val offCentre = abs((row.top + row.bottom) / 2 - (screen.top + screen.bottom) / 2)
            // Not the top, and not far from the middle: a few rows of slack for the header above the list.
            assertTrue(offCentre < screen.height / 4, "the row is $offCentre px from the middle of a ${screen.height} px screen")
        }
    }

    @Test
    fun viewLink_clickInvokesTransferCallback() {
        runMoneyManagerComposeUiTest {
            var clickedTransferId: TransferId? = null
            var clickedIsPositiveAmount: Boolean? = null

            setContent {
                MaterialTheme {
                    CsvPreviewTable(
                        columns =
                            listOf(
                                CsvColumn(
                                    id = CsvColumnId(Uuid.random()),
                                    columnIndex = 0,
                                    originalName = "Amount",
                                ),
                            ),
                        rows =
                            listOf(
                                CsvRow(
                                    rowIndex = 1,
                                    values = listOf("12.34"),
                                    transferId = TransferId(42),
                                    importStatus = ImportStatus.IMPORTED,
                                ),
                            ),
                        amountColumnIndex = 0,
                        onTransferClick = { transferId, isPositiveAmount ->
                            clickedTransferId = transferId
                            clickedIsPositiveAmount = isPositiveAmount
                        },
                    )
                }
            }

            onNodeWithText("View ->").performClick()
            waitForIdle()

            assertEquals(TransferId(42), clickedTransferId)
            assertEquals(true, clickedIsPositiveAmount)
        }
    }

    @Test
    fun duplicateLink_clickInvokesDuplicateSourceCallbackWithAmountSign() {
        runMoneyManagerComposeUiTest {
            var clickedTransferId: TransferId? = null
            var clickedIsPositiveAmount: Boolean? = null

            setContent {
                MaterialTheme {
                    CsvPreviewTable(
                        columns =
                            listOf(
                                CsvColumn(
                                    id = CsvColumnId(Uuid.random()),
                                    columnIndex = 0,
                                    originalName = "Amount",
                                ),
                            ),
                        rows =
                            listOf(
                                CsvRow(
                                    rowIndex = 1,
                                    values = listOf("-12.34"),
                                    transferId = TransferId(42),
                                    importStatus = ImportStatus.DUPLICATE,
                                ),
                            ),
                        amountColumnIndex = 0,
                        onDuplicateSourceClick = { transferId, isPositiveAmount ->
                            clickedTransferId = transferId
                            clickedIsPositiveAmount = isPositiveAmount
                        },
                    )
                }
            }

            onNodeWithText("Source ->").performClick()
            waitForIdle()

            assertEquals(TransferId(42), clickedTransferId)
            assertEquals(false, clickedIsPositiveAmount)
        }
    }

    @Test
    fun duplicateLink_withoutDuplicateSourceCallbackIsNotClickable() {
        runMoneyManagerComposeUiTest {
            setContent {
                MaterialTheme {
                    CsvPreviewTable(
                        columns =
                            listOf(
                                CsvColumn(
                                    id = CsvColumnId(Uuid.random()),
                                    columnIndex = 0,
                                    originalName = "Amount",
                                ),
                            ),
                        rows =
                            listOf(
                                CsvRow(
                                    rowIndex = 1,
                                    values = listOf("12.34"),
                                    transferId = TransferId(42),
                                    importStatus = ImportStatus.DUPLICATE,
                                ),
                            ),
                        amountColumnIndex = 0,
                        onTransferClick = { _, _ -> error("Duplicate rows should not fall through to transfer click") },
                    )
                }
            }

            onNodeWithText("View ->").assertHasNoClickAction()
        }
    }
}

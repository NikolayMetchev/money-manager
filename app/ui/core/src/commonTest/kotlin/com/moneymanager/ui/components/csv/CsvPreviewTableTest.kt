@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.components.csv

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.ImportStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class CsvPreviewTableTest {
    @Test
    fun viewLink_clickInvokesTransferCallback() =
        runComposeUiTest {
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

    @Test
    fun duplicateLink_clickInvokesDuplicateSourceCallbackWithAmountSign() =
        runComposeUiTest {
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

    @Test
    fun duplicateLink_withoutDuplicateSourceCallbackIsNotClickable() =
        runComposeUiTest {
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

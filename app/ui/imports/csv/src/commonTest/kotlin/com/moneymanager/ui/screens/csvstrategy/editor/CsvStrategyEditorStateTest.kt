package com.moneymanager.ui.screens.csvstrategy.editor

import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.csvstrategy.AccountRule
import com.moneymanager.domain.model.csvstrategy.AccountRulesMapping
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.rules.ValueExpr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Verifies the editor state holder seeds itself from a strategy, dropping references to columns the
 * uploaded CSV no longer has.
 */
class CsvStrategyEditorStateTest {
    @Test
    fun `state holder seeds itself from a strategy and drops missing columns`() {
        val now = Instant.fromEpochMilliseconds(1_000)
        val strategy =
            CsvImportStrategy(
                id = CsvImportStrategyId(Uuid.random()),
                name = "Simple",
                config =
                    CsvStrategyConfig(
                        identificationColumns = setOf("Date", "Payee"),
                        fieldMappings =
                            mapOf(
                                TransferField.TARGET_ACCOUNT to
                                    AccountRulesMapping(
                                        fieldType = TransferField.TARGET_ACCOUNT,
                                        rules = listOf(AccountRule(value = ValueExpr(listOf("Payee")))),
                                    ),
                                TransferField.TIMESTAMP to
                                    DateTimeParsingMapping(
                                        fieldType = TransferField.TIMESTAMP,
                                        dateColumnName = "Date",
                                        dateFormat = "yyyy-MM-dd",
                                    ),
                                TransferField.DESCRIPTION to
                                    DirectColumnMapping(fieldType = TransferField.DESCRIPTION, value = ValueExpr(listOf("Memo"))),
                                TransferField.AMOUNT to
                                    AmountParsingMapping(
                                        fieldType = TransferField.AMOUNT,
                                        mode = AmountMode.SINGLE_COLUMN,
                                        amountColumnName = "Amount",
                                    ),
                                TransferField.TIMEZONE to
                                    HardCodedTimezoneMapping(TransferField.TIMEZONE, "Europe/London"),
                            ),
                    ),
                createdAt = now,
                updatedAt = now,
            )
        // "Memo" is absent, so the description mapping's column reference must not survive the load.
        val columns = setOf("Date", "Payee", "Amount")

        val state = CsvStrategyEditorState(strategy, columns)

        assertEquals("Simple", state.name)
        assertEquals(setOf("Date", "Payee"), state.identificationColumns)
        assertEquals(listOf(AccountRule(value = ValueExpr(listOf("Payee")))), state.targetRules)
        assertEquals("Date", state.dateColumnName)
        assertEquals("yyyy-MM-dd", state.dateFormat)
        assertEquals("Amount", state.amountColumnName)
        assertEquals("Europe/London", state.selectedTimezone)
        assertNull(state.descriptionColumnName)
    }

    @Test
    fun `create-mode defaults identification columns to all columns`() {
        val columns = setOf("A", "B", "C")
        val state = CsvStrategyEditorState(strategy = null, availableColumnNames = columns)
        assertEquals(columns, state.identificationColumns)
    }
}

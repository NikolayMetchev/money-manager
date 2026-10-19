package com.moneymanager.ui.screens.csvstrategy.editor

import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.csvstrategy.AccountFromRulesMapping
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.rules.AccountNaming
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.StrategyRule
import com.moneymanager.domain.model.rules.ValueExpr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Verifies the editor state holder seeds itself from a strategy, dropping references to columns the
 * uploaded CSV no longer has.
 */
class CsvStrategyEditorStateTest {
    private val payeeRule = StrategyRule.accounts(AccountSide.TARGET, listOf(AccountNaming(value = ValueExpr(listOf("Payee")))))

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
                                    AccountFromRulesMapping(TransferField.TARGET_ACCOUNT),
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
                        rules = listOf(payeeRule),
                    ),
                createdAt = now,
                updatedAt = now,
            )
        // "Memo" is absent, so the description mapping's column reference must not survive the load.
        val columns = setOf("Date", "Payee", "Amount")

        val state = CsvStrategyEditorState(strategy, columns)

        assertEquals("Simple", state.name)
        assertEquals(setOf("Date", "Payee"), state.identificationColumns)
        assertEquals(listOf(payeeRule), state.rules)
        assertEquals("Date", state.dateColumnName)
        assertEquals("yyyy-MM-dd", state.dateFormat)
        assertEquals("Amount", state.amountColumnName)
        assertEquals("Europe/London", state.selectedTimezone)
        assertNull(state.descriptionColumnName)
    }

    @Test
    fun `the rules must name the target account and be complete before saving`() {
        val state = CsvStrategyEditorState(strategy = null, availableColumnNames = setOf("Amount", "Payee"))

        state.rules = emptyList()
        assertTrue(state.rulesHaveError, "no rule names the target account")
        assertFalse(state.isValid)

        state.rules = listOf(StrategyRule.accounts(AccountSide.TARGET, listOf(AccountNaming(value = ValueExpr(listOf(""))))))
        assertTrue(state.rulesHaveError, "a naming with no column is incomplete")

        state.rules = listOf(payeeRule)
        assertFalse(state.rulesHaveError)
    }

    @Test
    fun `create-mode defaults identification columns to all columns`() {
        val columns = setOf("A", "B", "C")
        val state = CsvStrategyEditorState(strategy = null, availableColumnNames = columns)
        assertEquals(columns, state.identificationColumns)
    }
}

@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens.csvstrategy.editor

import com.moneymanager.domain.model.csvstrategy.AccountLookupMapping
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMappingId
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Verifies the editor state holder faithfully mirrors a [StrategyFormState]: seeding it with an
 * extracted state and reading it back via [CsvStrategyEditorState.toFormState] yields the same value.
 */
class CsvStrategyEditorStateTest {
    private fun mappingId(value: Long) = FieldMappingId(Uuid.fromLongs(0L, value))

    @Test
    fun `state holder round-trips the extracted form state`() {
        val now = Instant.fromEpochMilliseconds(1_000)
        val strategy =
            CsvImportStrategy(
                id = CsvImportStrategyId(Uuid.random()),
                name = "Simple",
                identificationColumns = setOf("Date", "Payee"),
                fieldMappings =
                    mapOf(
                        TransferField.TARGET_ACCOUNT to
                            AccountLookupMapping(mappingId(1), TransferField.TARGET_ACCOUNT, "Payee"),
                        TransferField.TIMESTAMP to
                            DateTimeParsingMapping(
                                id = mappingId(2),
                                fieldType = TransferField.TIMESTAMP,
                                dateColumnName = "Date",
                                dateFormat = "yyyy-MM-dd",
                            ),
                        TransferField.DESCRIPTION to
                            DirectColumnMapping(mappingId(3), TransferField.DESCRIPTION, "Memo"),
                        TransferField.AMOUNT to
                            AmountParsingMapping(
                                id = mappingId(4),
                                fieldType = TransferField.AMOUNT,
                                mode = AmountMode.SINGLE_COLUMN,
                                amountColumnName = "Amount",
                            ),
                        TransferField.TIMEZONE to
                            HardCodedTimezoneMapping(mappingId(5), TransferField.TIMEZONE, "Europe/London"),
                    ),
                createdAt = now,
                updatedAt = now,
            )
        val columns = setOf("Date", "Payee", "Memo", "Amount")
        val extracted = extractFormStateFromStrategy(strategy, columns)

        val state = CsvStrategyEditorState(extracted, defaultIdentificationColumns = columns)

        assertEquals(extracted, state.toFormState())
    }

    @Test
    fun `create-mode defaults identification columns to all columns`() {
        val columns = setOf("A", "B", "C")
        val state = CsvStrategyEditorState(initial = null, defaultIdentificationColumns = columns)
        assertEquals(columns, state.toFormState().identificationColumns)
    }
}

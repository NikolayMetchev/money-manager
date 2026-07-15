@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens.csvstrategy

import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csvstrategy.AccountLookupMapping
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.AttributeAccountMatch
import com.moneymanager.domain.model.csvstrategy.AttributeColumnMapping
import com.moneymanager.domain.model.csvstrategy.AttributeMatchAccountMapping
import com.moneymanager.domain.model.csvstrategy.ColumnPairSwap
import com.moneymanager.domain.model.csvstrategy.CompanionTransactionRule
import com.moneymanager.domain.model.csvstrategy.ConditionalAccountMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMapping
import com.moneymanager.domain.model.csvstrategy.FieldMappingId
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.RegexAccountMapping
import com.moneymanager.domain.model.csvstrategy.RowCondition
import com.moneymanager.domain.model.csvstrategy.RowConditionOperator
import com.moneymanager.domain.model.csvstrategy.RowPreprocessingRule
import com.moneymanager.domain.model.csvstrategy.TemplateAccountMapping
import com.moneymanager.domain.model.csvstrategy.TimezoneLookupMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.ui.screens.csvstrategy.editor.buildStrategyFromFormState
import com.moneymanager.ui.screens.csvstrategy.editor.extractFormStateFromStrategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Verifies the Create/Edit Strategy dialog can round-trip every advanced mapping type:
 * extracting form state from a strategy and rebuilding it reproduces the original (the
 * dialog no longer carries any mapping over unchanged).
 */
class StrategyFormRoundTripTest {
    private val fixedId = FieldMappingId(Uuid.fromLongs(0L, 0L))
    private val timestamp = Instant.fromEpochMilliseconds(1_000)

    private fun mappingId(value: Long) = FieldMappingId(Uuid.fromLongs(0L, value))

    /** Rewrites every FieldMappingId to a constant so structural comparison ignores ids. */
    private fun normalize(mapping: FieldMapping): FieldMapping =
        when (mapping) {
            is AccountLookupMapping -> mapping.copy(id = fixedId)
            is RegexAccountMapping -> mapping.copy(id = fixedId)
            is AttributeMatchAccountMapping -> mapping.copy(id = fixedId)
            is TemplateAccountMapping -> mapping.copy(id = fixedId)
            is ConditionalAccountMapping ->
                mapping.copy(
                    id = fixedId,
                    whenTrue = normalize(mapping.whenTrue),
                    whenFalse = normalize(mapping.whenFalse),
                )
            is DateTimeParsingMapping -> mapping.copy(id = fixedId)
            is DirectColumnMapping -> mapping.copy(id = fixedId)
            is AmountParsingMapping -> mapping.copy(id = fixedId)
            is HardCodedCurrencyMapping -> mapping.copy(id = fixedId)
            is CurrencyLookupMapping -> mapping.copy(id = fixedId)
            is HardCodedTimezoneMapping -> mapping.copy(id = fixedId)
            is TimezoneLookupMapping -> mapping.copy(id = fixedId)
            else -> error("Unexpected mapping type: $mapping")
        }

    private fun normalize(mappings: Map<TransferField, FieldMapping>): Map<TransferField, FieldMapping> =
        mappings.mapValues { (_, mapping) -> normalize(mapping) }

    private val columns =
        listOf(
            "Direction",
            "Created on",
            "Reference",
            "Source amount (after fees)",
            "Source fee amount",
            "Source name",
            "Source currency",
            "Target name",
            "Target currency",
            "ID",
        ).mapIndexed { index, name -> CsvColumn(CsvColumnId(Uuid.random()), index, name) }

    /** A strategy exercising all six advanced mapping types. */
    private fun advancedStrategy(): CsvImportStrategy =
        CsvImportStrategy(
            id = CsvImportStrategyId(Uuid.random()),
            name = "Advanced",
            identificationColumns = setOf("Direction", "Created on"),
            fieldMappings =
                mapOf(
                    TransferField.SOURCE_ACCOUNT to
                        TemplateAccountMapping(mappingId(1), TransferField.SOURCE_ACCOUNT, "Source currency", prefix = "Wise: "),
                    TransferField.TARGET_ACCOUNT to
                        ConditionalAccountMapping(
                            id = mappingId(2),
                            fieldType = TransferField.TARGET_ACCOUNT,
                            conditions =
                                listOf(
                                    RowCondition("Source name", RowConditionOperator.EQUALS_COLUMN, otherColumnName = "Target name"),
                                    RowCondition("Source name", RowConditionOperator.IS_NOT_BLANK),
                                ),
                            whenTrue =
                                TemplateAccountMapping(
                                    mappingId(3),
                                    TransferField.TARGET_ACCOUNT,
                                    "Target currency",
                                    prefix = "Wise: ",
                                ),
                            whenFalse =
                                AccountLookupMapping(
                                    mappingId(4),
                                    TransferField.TARGET_ACCOUNT,
                                    "Target name",
                                    fallbackColumns = listOf("Source name"),
                                ),
                        ),
                    TransferField.TIMESTAMP to
                        DateTimeParsingMapping(
                            id = mappingId(5),
                            fieldType = TransferField.TIMESTAMP,
                            dateColumnName = "Created on",
                            dateFormat = "yyyy-MM-dd",
                            dateTimeFormat = "yyyy-MM-dd HH:mm:ss",
                        ),
                    TransferField.DESCRIPTION to
                        DirectColumnMapping(mappingId(6), TransferField.DESCRIPTION, "Reference"),
                    TransferField.AMOUNT to
                        AmountParsingMapping(
                            id = mappingId(7),
                            fieldType = TransferField.AMOUNT,
                            mode = AmountMode.SINGLE_COLUMN,
                            amountColumnName = "Source amount (after fees)",
                            feeColumnName = "Source fee amount",
                            feeConditions = listOf(RowCondition("Direction", RowConditionOperator.EQUALS_VALUE, value = "OUT")),
                        ),
                    TransferField.CURRENCY to
                        CurrencyLookupMapping(mappingId(8), TransferField.CURRENCY, "Source currency"),
                    TransferField.TIMEZONE to
                        HardCodedTimezoneMapping(mappingId(9), TransferField.TIMEZONE, "Europe/London"),
                ),
            attributeMappings =
                listOf(AttributeColumnMapping(columnName = "ID", attributeTypeName = "wise-id", isUniqueIdentifier = true)),
            rowPreprocessingRules =
                listOf(
                    RowPreprocessingRule(
                        conditions = listOf(RowCondition("Direction", RowConditionOperator.EQUALS_VALUE, value = "IN")),
                        columnSwaps =
                            listOf(
                                ColumnPairSwap("Source name", "Target name"),
                                ColumnPairSwap("Source currency", "Target currency"),
                            ),
                        flipSourceAndTarget = true,
                    ),
                ),
            companionTransactionRules =
                listOf(
                    CompanionTransactionRule(
                        name = "Interest earned",
                        matchAttributeName = "wise-id",
                        matchValuePattern = "ACCRUAL_CHARGE-%",
                        linkAttributeName = "wise-interest-for",
                        companionDescription = "Interest earned",
                    ),
                ),
            createdAt = timestamp,
            updatedAt = timestamp,
        )

    @Test
    fun `extracting then rebuilding reproduces every advanced mapping type`() {
        val original = advancedStrategy()
        val availableColumns = columns.map { it.originalName }.toSet()

        val state = extractFormStateFromStrategy(original, availableColumns)
        val rebuilt = buildStrategyFromFormState(state, original.id, original.createdAt, original.updatedAt)

        assertEquals(normalize(original.fieldMappings), normalize(rebuilt.fieldMappings))
        assertEquals(original.identificationColumns, rebuilt.identificationColumns)
        assertEquals(original.attributeMappings, rebuilt.attributeMappings)
        assertEquals(original.rowPreprocessingRules, rebuilt.rowPreprocessingRules)
        assertEquals(original.companionTransactionRules, rebuilt.companionTransactionRules)

        // Spot-check the trickiest type survived the nested round trip.
        val target = rebuilt.fieldMappings[TransferField.TARGET_ACCOUNT]
        assertIs<ConditionalAccountMapping>(target)
        assertIs<TemplateAccountMapping>(target.whenTrue)
        assertIs<AccountLookupMapping>(target.whenFalse)
    }

    @Test
    fun `attribute-match target mode and funding match round-trip`() {
        val original =
            CsvImportStrategy(
                id = CsvImportStrategyId(Uuid.random()),
                name = "Attr",
                identificationColumns = setOf("Direction", "Created on"),
                fieldMappings =
                    mapOf(
                        TransferField.SOURCE_ACCOUNT to
                            TemplateAccountMapping(mappingId(1), TransferField.SOURCE_ACCOUNT, "Source currency", prefix = "Wise: "),
                        TransferField.TARGET_ACCOUNT to
                            AttributeMatchAccountMapping(
                                id = mappingId(2),
                                fieldType = TransferField.TARGET_ACCOUNT,
                                columnName = "Target name",
                                attributeTypeName = "card-last4",
                            ),
                        TransferField.TIMESTAMP to
                            DateTimeParsingMapping(
                                id = mappingId(5),
                                fieldType = TransferField.TIMESTAMP,
                                dateColumnName = "Created on",
                                dateFormat = "yyyy-MM-dd",
                            ),
                        TransferField.DESCRIPTION to
                            DirectColumnMapping(mappingId(6), TransferField.DESCRIPTION, "Reference"),
                        TransferField.AMOUNT to
                            AmountParsingMapping(
                                id = mappingId(7),
                                fieldType = TransferField.AMOUNT,
                                mode = AmountMode.SINGLE_COLUMN,
                                amountColumnName = "Source amount (after fees)",
                            ),
                        TransferField.CURRENCY to
                            CurrencyLookupMapping(mappingId(8), TransferField.CURRENCY, "Source currency"),
                        TransferField.TIMEZONE to
                            HardCodedTimezoneMapping(mappingId(9), TransferField.TIMEZONE, "Europe/London"),
                    ),
                fundingAttributeMatch = AttributeAccountMatch(column = "Reference", attributeTypeName = "card-last4"),
                createdAt = timestamp,
                updatedAt = timestamp,
            )
        val availableColumns = columns.map { it.originalName }.toSet()

        val state = extractFormStateFromStrategy(original, availableColumns)
        val rebuilt = buildStrategyFromFormState(state, original.id, original.createdAt, original.updatedAt)

        assertEquals(normalize(original.fieldMappings), normalize(rebuilt.fieldMappings))
        assertEquals(original.fundingAttributeMatch, rebuilt.fundingAttributeMatch)
        val target = rebuilt.fieldMappings[TransferField.TARGET_ACCOUNT]
        assertIs<AttributeMatchAccountMapping>(target)
        assertEquals("card-last4", target.attributeTypeName)
        assertEquals("Target name", target.columnName)
    }

    @Test
    fun `conditions referencing dropped columns are removed on extract`() {
        val original = advancedStrategy()
        // A CSV missing "Target name" invalidates the EQUALS_COLUMN condition and a column swap.
        val availableColumns =
            original.identificationColumns +
                setOf(
                    "Reference",
                    "Source amount (after fees)",
                    "Source fee amount",
                    "Source name",
                    "Source currency",
                    "Target currency",
                    "ID",
                )

        val state = extractFormStateFromStrategy(original, availableColumns)

        // The condition comparing against "Target name" is dropped; the IS_NOT_BLANK one stays.
        assertEquals(1, state.targetConditions.size)
        assertEquals(RowConditionOperator.IS_NOT_BLANK, state.targetConditions.single().operator)
        // The preprocessing rule referenced "Target name" in a swap, so the whole rule is dropped.
        assertEquals(emptyList(), state.rowPreprocessingRules)
        // The conditional's whenFalse branch looked up the now-missing "Target name" column, so
        // the stale reference is cleared rather than carried through extraction.
        val whenFalse = state.targetWhenFalse
        assertIs<AccountLookupMapping>(whenFalse)
        assertEquals("", whenFalse.columnName)
        // "Source name" still exists, so the fallback is retained.
        assertEquals(listOf("Source name"), whenFalse.fallbackColumns)
    }
}

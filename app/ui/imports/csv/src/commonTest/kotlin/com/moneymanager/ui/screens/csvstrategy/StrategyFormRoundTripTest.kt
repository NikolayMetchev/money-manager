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
import com.moneymanager.domain.model.csvstrategy.ContentMatchRule
import com.moneymanager.domain.model.csvstrategy.ConversionAccountRule
import com.moneymanager.domain.model.csvstrategy.ConversionConfig
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.RowCondition
import com.moneymanager.domain.model.csvstrategy.RowConditionOperator
import com.moneymanager.domain.model.csvstrategy.RowPreprocessingRule
import com.moneymanager.domain.model.csvstrategy.TemplateAccountMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.ui.screens.csvstrategy.editor.CsvStrategyEditorState
import com.moneymanager.ui.screens.csvstrategy.editor.buildStrategyFromEditorState
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
    private val timestamp = Instant.fromEpochMilliseconds(1_000)

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
                        TemplateAccountMapping(TransferField.SOURCE_ACCOUNT, "Source currency", prefix = "Wise: "),
                    TransferField.TARGET_ACCOUNT to
                        ConditionalAccountMapping(
                            fieldType = TransferField.TARGET_ACCOUNT,
                            conditions =
                                listOf(
                                    RowCondition("Source name", RowConditionOperator.EQUALS_COLUMN, otherColumnName = "Target name"),
                                    RowCondition("Source name", RowConditionOperator.IS_NOT_BLANK),
                                ),
                            whenTrue =
                                TemplateAccountMapping(
                                    TransferField.TARGET_ACCOUNT,
                                    "Target currency",
                                    prefix = "Wise: ",
                                ),
                            whenFalse =
                                AccountLookupMapping(
                                    TransferField.TARGET_ACCOUNT,
                                    "Target name",
                                    fallbackColumns = listOf("Source name"),
                                ),
                        ),
                    TransferField.TIMESTAMP to
                        DateTimeParsingMapping(
                            fieldType = TransferField.TIMESTAMP,
                            dateColumnName = "Created on",
                            dateFormat = "yyyy-MM-dd",
                            dateTimeFormat = "yyyy-MM-dd HH:mm:ss",
                        ),
                    TransferField.DESCRIPTION to
                        DirectColumnMapping(TransferField.DESCRIPTION, "Reference"),
                    TransferField.AMOUNT to
                        AmountParsingMapping(
                            fieldType = TransferField.AMOUNT,
                            mode = AmountMode.SINGLE_COLUMN,
                            amountColumnName = "Source amount (after fees)",
                            feeColumnName = "Source fee amount",
                            feeConditions = listOf(RowCondition("Direction", RowConditionOperator.EQUALS_VALUE, value = "OUT")),
                        ),
                    TransferField.CURRENCY to
                        CurrencyLookupMapping(TransferField.CURRENCY, "Source currency"),
                    TransferField.TIMEZONE to
                        HardCodedTimezoneMapping(TransferField.TIMEZONE, "Europe/London"),
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

        val state = CsvStrategyEditorState(original, availableColumns)
        val rebuilt = buildStrategyFromEditorState(state, original.id, original.createdAt, original.updatedAt)

        assertEquals(original.fieldMappings, rebuilt.fieldMappings)
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
                            TemplateAccountMapping(TransferField.SOURCE_ACCOUNT, "Source currency", prefix = "Wise: "),
                        TransferField.TARGET_ACCOUNT to
                            AttributeMatchAccountMapping(
                                fieldType = TransferField.TARGET_ACCOUNT,
                                columnName = "Target name",
                                attributeTypeName = "card-last4",
                            ),
                        TransferField.TIMESTAMP to
                            DateTimeParsingMapping(
                                fieldType = TransferField.TIMESTAMP,
                                dateColumnName = "Created on",
                                dateFormat = "yyyy-MM-dd",
                            ),
                        TransferField.DESCRIPTION to
                            DirectColumnMapping(TransferField.DESCRIPTION, "Reference"),
                        TransferField.AMOUNT to
                            AmountParsingMapping(
                                fieldType = TransferField.AMOUNT,
                                mode = AmountMode.SINGLE_COLUMN,
                                amountColumnName = "Source amount (after fees)",
                            ),
                        TransferField.CURRENCY to
                            CurrencyLookupMapping(TransferField.CURRENCY, "Source currency"),
                        TransferField.TIMEZONE to
                            HardCodedTimezoneMapping(TransferField.TIMEZONE, "Europe/London"),
                    ),
                fundingAttributeMatch = AttributeAccountMatch(column = "Reference", attributeTypeName = "card-last4"),
                createdAt = timestamp,
                updatedAt = timestamp,
            )
        val availableColumns = columns.map { it.originalName }.toSet()

        val state = CsvStrategyEditorState(original, availableColumns)
        val rebuilt = buildStrategyFromEditorState(state, original.id, original.createdAt, original.updatedAt)

        assertEquals(original.fieldMappings, rebuilt.fieldMappings)
        assertEquals(original.fundingAttributeMatch, rebuilt.fundingAttributeMatch)
        val target = rebuilt.fieldMappings[TransferField.TARGET_ACCOUNT]
        assertIs<AttributeMatchAccountMapping>(target)
        assertEquals("card-last4", target.attributeTypeName)
        assertEquals("Target name", target.columnName)
    }

    @Test
    fun `conversion config plus content-match rules and cross-source window round-trip`() {
        val original =
            advancedStrategy().copy(
                contentMatchRules =
                    listOf(
                        ContentMatchRule(columnName = "Direction", pattern = "OUT"),
                        ContentMatchRule(columnName = "Reference", pattern = "CRV\\*"),
                    ),
                crossSourceReconcileWindowSeconds = 120,
                conversionConfig =
                    ConversionConfig(
                        signalColumn = "Direction",
                        debitPattern = "(?i)_debited$",
                        creditPattern = "(?i)_credited$",
                        conversionAccountName = "Crypto.com Conversions",
                        conversionAccountRules =
                            listOf(
                                ConversionAccountRule(column = "Source currency", pattern = "(?i)^DUST$", accountName = "Dust"),
                            ),
                        pairingKeyPattern = "(?i)^(.*)_(?:debited|credited)$",
                        pairingKeyColumns = listOf("Reference"),
                        pairingWindowSeconds = 60,
                        relationshipTypeName = "conversion",
                    ),
            )
        val availableColumns = columns.map { it.originalName }.toSet()

        val state = CsvStrategyEditorState(original, availableColumns)
        val rebuilt = buildStrategyFromEditorState(state, original.id, original.createdAt, original.updatedAt)

        assertEquals(original.contentMatchRules, rebuilt.contentMatchRules)
        assertEquals(original.crossSourceReconcileWindowSeconds, rebuilt.crossSourceReconcileWindowSeconds)
        assertEquals(original.conversionConfig, rebuilt.conversionConfig)
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

        val state = CsvStrategyEditorState(original, availableColumns)

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

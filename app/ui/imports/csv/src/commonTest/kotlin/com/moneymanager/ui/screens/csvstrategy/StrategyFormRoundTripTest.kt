package com.moneymanager.ui.screens.csvstrategy

import com.moneymanager.builtin.BuiltInCsvStrategies
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csvstrategy.AccountRule
import com.moneymanager.domain.model.csvstrategy.AccountRulesMapping
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.AttributeAccountMatch
import com.moneymanager.domain.model.csvstrategy.AttributeColumnMapping
import com.moneymanager.domain.model.csvstrategy.ColumnPairSwap
import com.moneymanager.domain.model.csvstrategy.CompanionTransactionRule
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.LegAssembly
import com.moneymanager.domain.model.csvstrategy.LegGroupRule
import com.moneymanager.domain.model.csvstrategy.LegSide
import com.moneymanager.domain.model.csvstrategy.RowPreprocessingRule
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.rules.AssetCodeRules
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.Direction
import com.moneymanager.domain.model.rules.Extraction
import com.moneymanager.domain.model.rules.FeeRule
import com.moneymanager.domain.model.rules.ValueExpr
import com.moneymanager.ui.screens.csvstrategy.editor.CsvStrategyEditorState
import com.moneymanager.ui.screens.csvstrategy.editor.buildStrategyFromEditorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
            config =
                CsvStrategyConfig(
                    identificationColumns = setOf("Direction", "Created on"),
                    fieldMappings =
                        mapOf(
                            TransferField.SOURCE_ACCOUNT to
                                AccountRulesMapping(
                                    fieldType = TransferField.SOURCE_ACCOUNT,
                                    rules =
                                        listOf(
                                            AccountRule(value = ValueExpr(listOf("Source currency")), trim = true, name = "Wise: {value}"),
                                        ),
                                ),
                            TransferField.TARGET_ACCOUNT to
                                AccountRulesMapping.conditional(
                                    fieldType = TransferField.TARGET_ACCOUNT,
                                    conditions =
                                        listOf(
                                            Condition("Source name", ConditionOp.EQUALS_PATH, otherPath = "Target name"),
                                            Condition("Source name", ConditionOp.NOT_BLANK),
                                        ),
                                    whenTrue =
                                        AccountRulesMapping(
                                            fieldType = TransferField.TARGET_ACCOUNT,
                                            rules =
                                                listOf(
                                                    AccountRule(
                                                        value = ValueExpr(listOf("Target currency")),
                                                        trim = true,
                                                        name = "Wise: {value}",
                                                    ),
                                                ),
                                        ),
                                    whenFalse =
                                        AccountRulesMapping(
                                            fieldType = TransferField.TARGET_ACCOUNT,
                                            rules = listOf(AccountRule(value = ValueExpr(listOf("Target name", "Source name")))),
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
                                DirectColumnMapping(fieldType = TransferField.DESCRIPTION, value = ValueExpr(listOf("Reference"))),
                            TransferField.AMOUNT to
                                AmountParsingMapping(
                                    fieldType = TransferField.AMOUNT,
                                    mode = AmountMode.SINGLE_COLUMN,
                                    amountColumnName = "Source amount (after fees)",
                                    fee =
                                        FeeRule(
                                            amount = ValueExpr(listOf("Source fee amount")),
                                            conditions = listOf(Condition("Direction", ConditionOp.EQUALS, value = "OUT")),
                                        ),
                                ),
                            TransferField.CURRENCY to
                                CurrencyLookupMapping(fieldType = TransferField.CURRENCY, value = ValueExpr(listOf("Source currency"))),
                            TransferField.TIMEZONE to
                                HardCodedTimezoneMapping(TransferField.TIMEZONE, "Europe/London"),
                        ),
                    attributeMappings =
                        listOf(AttributeColumnMapping(columnName = "ID", attributeTypeName = "wise-id", isUniqueIdentifier = true)),
                    rowPreprocessingRules =
                        listOf(
                            RowPreprocessingRule(
                                conditions = listOf(Condition("Direction", ConditionOp.EQUALS, value = "IN")),
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

        assertEquals(original.config.fieldMappings, rebuilt.config.fieldMappings)
        assertEquals(original.config.identificationColumns, rebuilt.config.identificationColumns)
        assertEquals(original.config.attributeMappings, rebuilt.config.attributeMappings)
        assertEquals(original.config.rowPreprocessingRules, rebuilt.config.rowPreprocessingRules)
        assertEquals(original.config.companionTransactionRules, rebuilt.config.companionTransactionRules)

        // Spot-check the conditional rule survived: guarded template first, plain lookup after.
        val target = rebuilt.config.fieldMappings[TransferField.TARGET_ACCOUNT]
        assertIs<AccountRulesMapping>(target)
        assertEquals(
            2,
            target.rules
                .first()
                .conditions.size,
        )
        assertEquals(emptyList(), target.rules.last().conditions)
    }

    @Test
    fun `attribute-match target mode and funding match round-trip`() {
        val original =
            CsvImportStrategy(
                id = CsvImportStrategyId(Uuid.random()),
                name = "Attr",
                config =
                    CsvStrategyConfig(
                        identificationColumns = setOf("Direction", "Created on"),
                        fieldMappings =
                            mapOf(
                                TransferField.SOURCE_ACCOUNT to
                                    AccountRulesMapping(
                                        fieldType = TransferField.SOURCE_ACCOUNT,
                                        rules =
                                            listOf(
                                                AccountRule(
                                                    value = ValueExpr(listOf("Source currency")),
                                                    trim = true,
                                                    name = "Wise: {value}",
                                                ),
                                            ),
                                    ),
                                TransferField.TARGET_ACCOUNT to
                                    AccountRulesMapping(
                                        fieldType = TransferField.TARGET_ACCOUNT,
                                        rules =
                                            listOf(
                                                AccountRule(
                                                    value = ValueExpr(listOf("Target name")),
                                                    trim = true,
                                                    attributeTypeName = "card-last4",
                                                ),
                                                AccountRule(value = ValueExpr(listOf("Target name")), trim = true),
                                            ),
                                    ),
                                TransferField.TIMESTAMP to
                                    DateTimeParsingMapping(
                                        fieldType = TransferField.TIMESTAMP,
                                        dateColumnName = "Created on",
                                        dateFormat = "yyyy-MM-dd",
                                    ),
                                TransferField.DESCRIPTION to
                                    DirectColumnMapping(fieldType = TransferField.DESCRIPTION, value = ValueExpr(listOf("Reference"))),
                                TransferField.AMOUNT to
                                    AmountParsingMapping(
                                        fieldType = TransferField.AMOUNT,
                                        mode = AmountMode.SINGLE_COLUMN,
                                        amountColumnName = "Source amount (after fees)",
                                    ),
                                TransferField.CURRENCY to
                                    CurrencyLookupMapping(fieldType = TransferField.CURRENCY, value = ValueExpr(listOf("Source currency"))),
                                TransferField.TIMEZONE to
                                    HardCodedTimezoneMapping(TransferField.TIMEZONE, "Europe/London"),
                            ),
                        fundingAttributeMatch = AttributeAccountMatch(column = "Reference", attributeTypeName = "card-last4"),
                    ),
                createdAt = timestamp,
                updatedAt = timestamp,
            )
        val availableColumns = columns.map { it.originalName }.toSet()

        val state = CsvStrategyEditorState(original, availableColumns)
        val rebuilt = buildStrategyFromEditorState(state, original.id, original.createdAt, original.updatedAt)

        assertEquals(original.config.fieldMappings, rebuilt.config.fieldMappings)
        assertEquals(original.config.fundingAttributeMatch, rebuilt.config.fundingAttributeMatch)
        val target = rebuilt.config.fieldMappings[TransferField.TARGET_ACCOUNT]
        assertIs<AccountRulesMapping>(target)
        assertEquals("card-last4", target.rules.first().attributeTypeName)
        assertEquals(
            "Target name",
            target.rules
                .first()
                .value.primaryPath,
        )
    }

    @Test
    fun `leg groups plus content-match rules and cross-source window round-trip`() {
        val original =
            advancedStrategy().copy(
                config =
                    advancedStrategy().config.copy(
                        contentMatchRules =
                            listOf(
                                Condition("Direction", ConditionOp.MATCHES, "OUT"),
                                Condition("Reference", ConditionOp.MATCHES, "CRV\\*"),
                            ),
                        crossSourceReconcileWindowSeconds = 120,
                        legGroups =
                            listOf(
                                LegGroupRule(
                                    legWhen = listOf(Condition("Direction", ConditionOp.MATCHES, "(?i)_(debited|credited)$")),
                                    side = LegSide.DebitWhen(listOf(Condition("Direction", ConditionOp.MATCHES, "(?i)_debited$"))),
                                    key =
                                        listOf(
                                            ValueExpr(listOf("Direction"), Extraction("(?i)^(.*)_(?:debited|credited)$", "$1")),
                                            ValueExpr(listOf("Reference")),
                                        ),
                                    windowSeconds = 60,
                                    assembly =
                                        LegAssembly.ThroughAccount(
                                            accounts =
                                                listOf(
                                                    AccountRule(
                                                        conditions =
                                                            listOf(
                                                                Condition("Source currency", ConditionOp.MATCHES, "(?i)^DUST$"),
                                                            ),
                                                        value = ValueExpr(listOf("Direction")),
                                                        name = "Dust",
                                                    ),
                                                    AccountRule(value = ValueExpr(listOf("Direction")), name = "Crypto.com Conversions"),
                                                ),
                                            relationshipTypeName = "conversion",
                                        ),
                                    reconcileWindowSeconds = 5,
                                ),
                                LegGroupRule(
                                    legWhen = listOf(Condition("Direction", ConditionOp.EQUALS, "TRADE")),
                                    side = LegSide.Sign("Source amount (after fees)"),
                                    windowSeconds = 1,
                                    assembly = LegAssembly.Trade("Swap {from} for {to}"),
                                ),
                            ),
                    ),
            )
        val availableColumns = columns.map { it.originalName }.toSet()

        val state = CsvStrategyEditorState(original, availableColumns)
        val rebuilt = buildStrategyFromEditorState(state, original.id, original.createdAt, original.updatedAt)

        assertEquals(original.config.contentMatchRules, rebuilt.config.contentMatchRules)
        assertEquals(original.config.crossSourceReconcileWindowSeconds, rebuilt.config.crossSourceReconcileWindowSeconds)
        assertEquals(original.config.legGroups, rebuilt.config.legGroups)
    }

    /**
     * Every property the editor has no widget for is still persisted, so a no-op Save must not
     * quietly replace it with a default (issue #735): the sign of every imported amount, the
     * description cleanup regex, the time given to date-only rows, the category new accounts land
     * in, and the worksheet an Excel strategy targets all have to survive load -> build untouched.
     */
    @Test
    fun `the built-in Koinly strategy survives a no-op save`() {
        // Its conditional source, credited-leg (trade) mappings, extractions, fee currency, aliases and
        // reconciliation config have no dedicated widgets; saving unchanged must keep every one of them.
        val koinly = BuiltInCsvStrategies.buildKoinlyCsvStrategy(timestamp)

        val state = CsvStrategyEditorState(koinly, koinly.config.identificationColumns)
        val rebuilt = buildStrategyFromEditorState(state, koinly.id, koinly.createdAt, koinly.updatedAt)

        assertEquals(koinly.config, rebuilt.config)
    }

    @Test
    fun `properties with no widget survive a no-op save`() {
        val original =
            advancedStrategy().copy(
                worksheetName = "Statement",
                config =
                    advancedStrategy().config.copy(
                        fieldMappings =
                            advancedStrategy().config.fieldMappings +
                                mapOf(
                                    TransferField.SOURCE_ACCOUNT to
                                        AccountRulesMapping(
                                            fieldType = TransferField.SOURCE_ACCOUNT,
                                            rules =
                                                listOf(
                                                    AccountRule(
                                                        value = ValueExpr(listOf("Source currency")),
                                                        trim = true,
                                                        name = "Wise: {value}",
                                                    ),
                                                ),
                                            defaultCategoryId = 41L,
                                        ),
                                    TransferField.TARGET_ACCOUNT to
                                        AccountRulesMapping(
                                            fieldType = TransferField.TARGET_ACCOUNT,
                                            rules = listOf(AccountRule(value = ValueExpr(listOf("Target name", "Source name")))),
                                            defaultCategoryId = 42L,
                                        ),
                                    TransferField.TIMESTAMP to
                                        DateTimeParsingMapping(
                                            fieldType = TransferField.TIMESTAMP,
                                            dateColumnName = "Created on",
                                            dateFormat = "yyyy-MM-dd",
                                            defaultTime = "03:30:00",
                                        ),
                                    TransferField.DESCRIPTION to
                                        DirectColumnMapping(
                                            fieldType = TransferField.DESCRIPTION,
                                            value =
                                                ValueExpr(
                                                    listOf("Reference"),
                                                    extraction = Extraction(pattern = "^(.*?),\\s*[0-9.]+$", outputTemplate = "$1"),
                                                ),
                                        ),
                                    TransferField.AMOUNT to
                                        AmountParsingMapping(
                                            fieldType = TransferField.AMOUNT,
                                            mode = AmountMode.SINGLE_COLUMN,
                                            amountColumnName = "Source amount (after fees)",
                                            direction = Direction.AmountSign(positiveIsIncoming = false),
                                        ),
                                    // A fallback currency column has no widget of its own.
                                    TransferField.CURRENCY to
                                        CurrencyLookupMapping(
                                            fieldType = TransferField.CURRENCY,
                                            value = ValueExpr(listOf("Source currency", "Target currency")),
                                        ),
                                ),
                        // Neither do asset suffixes to strip.
                        assetCodes = AssetCodeRules(aliases = mapOf("XXBT" to "BTC"), stripSuffixes = setOf(".F")),
                    ),
            )
        val availableColumns = columns.map { it.originalName }.toSet()

        val state = CsvStrategyEditorState(original, availableColumns)
        val rebuilt = buildStrategyFromEditorState(state, original.id, original.createdAt, original.updatedAt)

        assertEquals(original.config.fieldMappings, rebuilt.config.fieldMappings)
        assertEquals(original.config.assetCodes, rebuilt.config.assetCodes)
        assertEquals(original.worksheetName, rebuilt.worksheetName)

        // Pin the extraction itself, not just that the mapping round-trips: a dropped extraction
        // would leave this null. `$1` needs no escaping — a `$` before a digit cannot start a
        // template, which is why the model's own `outputTemplate` default is written `"$0"`.
        val description = rebuilt.config.fieldMappings[TransferField.DESCRIPTION]
        assertIs<DirectColumnMapping>(description)
        assertEquals("$1", description.value.extraction?.outputTemplate)
    }

    /** The same, for the target modes that each carry their own `defaultCategoryId`. */
    @Test
    fun `default category survives on every target account mode`() {
        val availableColumns = columns.map { it.originalName }.toSet()
        val targets =
            listOf(
                AccountRulesMapping(
                    fieldType = TransferField.TARGET_ACCOUNT,
                    rules = listOf(AccountRule(value = ValueExpr(listOf("Target name")))),
                    defaultCategoryId = 7L,
                ),
                AccountRulesMapping(
                    fieldType = TransferField.TARGET_ACCOUNT,
                    rules =
                        listOf(
                            AccountRule(value = ValueExpr(listOf("Target name")), pattern = "^AMZN", name = "Amazon"),
                            AccountRule(value = ValueExpr(listOf("Target name"))),
                        ),
                    defaultCategoryId = 8L,
                ),
                AccountRulesMapping(
                    fieldType = TransferField.TARGET_ACCOUNT,
                    rules = listOf(AccountRule(value = ValueExpr(listOf("Target name")), attributeTypeName = "card-last4")),
                    defaultCategoryId = 9L,
                ),
                AccountRulesMapping(
                    fieldType = TransferField.TARGET_ACCOUNT,
                    rules = listOf(AccountRule(value = ValueExpr(listOf("Target currency")), trim = true, name = "Wise: {value}")),
                    defaultCategoryId = 10L,
                ),
            )

        for (target in targets) {
            val base = advancedStrategy()
            val original =
                base.copy(
                    config =
                        base.config.copy(
                            fieldMappings = base.config.fieldMappings + (TransferField.TARGET_ACCOUNT to target),
                        ),
                )
            val state = CsvStrategyEditorState(original, availableColumns)
            val rebuilt = buildStrategyFromEditorState(state, original.id, original.createdAt, original.updatedAt)

            assertEquals(target, rebuilt.config.fieldMappings[TransferField.TARGET_ACCOUNT])
        }
    }

    /**
     * A credit/debit strategy has no single amount column, so before #735 it could neither be
     * validated nor rebuilt. It must now round-trip and report the form as valid.
     */
    @Test
    fun `credit-debit amount mode round-trips and validates`() {
        val amount =
            AmountParsingMapping(
                fieldType = TransferField.AMOUNT,
                mode = AmountMode.CREDIT_DEBIT_COLUMNS,
                creditColumnName = "Source amount (after fees)",
                debitColumnName = "Source fee amount",
            )
        val original =
            advancedStrategy().copy(
                config =
                    advancedStrategy().config.copy(
                        fieldMappings = advancedStrategy().config.fieldMappings + (TransferField.AMOUNT to amount),
                    ),
            )
        val availableColumns = columns.map { it.originalName }.toSet()

        val state = CsvStrategyEditorState(original, availableColumns)

        assertEquals(AmountMode.CREDIT_DEBIT_COLUMNS, state.amountMode)
        assertTrue(state.isValid, "a credit/debit strategy must be savable")

        val rebuilt = buildStrategyFromEditorState(state, original.id, original.createdAt, original.updatedAt)
        assertEquals(amount, rebuilt.config.fieldMappings[TransferField.AMOUNT])
    }

    /**
     * Switching a single-column strategy to credit/debit drops the now-meaningless amount column.
     *
     * The name avoids an apostrophe on purpose: dex cannot represent one in a method name, so it
     * would build on the JVM and fail only in the Android instrumented run.
     */
    @Test
    fun `switching amount mode clears the columns of the other mode`() {
        val original = advancedStrategy()
        val availableColumns = columns.map { it.originalName }.toSet()

        val state = CsvStrategyEditorState(original, availableColumns)
        state.amountMode = AmountMode.CREDIT_DEBIT_COLUMNS
        assertFalse(state.isValid, "credit and debit columns are still unset")

        state.creditColumnName = "Source amount (after fees)"
        state.debitColumnName = "Source fee amount"
        val rebuilt = buildStrategyFromEditorState(state, original.id, original.createdAt, original.updatedAt)

        val amount = rebuilt.config.fieldMappings[TransferField.AMOUNT]
        assertIs<AmountParsingMapping>(amount)
        assertNull(amount.amountColumnName)
        assertEquals("Source amount (after fees)", amount.creditColumnName)
        assertEquals("Source fee amount", amount.debitColumnName)
    }

    @Test
    fun `conditions referencing dropped columns are removed on extract`() {
        val original = advancedStrategy()
        // A CSV missing "Target name" invalidates the EQUALS_COLUMN condition and a column swap.
        val availableColumns =
            original.config.identificationColumns +
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

        // The guarded rule compared against the missing "Target name", so it can't be evaluated and is
        // dropped; the plain lookup loses its missing primary column but keeps its "Source name" fallback.
        assertEquals(listOf(AccountRule(value = ValueExpr(listOf("Source name")))), state.targetRules)
        // The preprocessing rule referenced "Target name" in a swap, so the whole rule is dropped.
        assertEquals(emptyList(), state.rowPreprocessingRules)
    }
}

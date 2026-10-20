package com.moneymanager.ui.screens.csvstrategy

import com.moneymanager.builtin.BuiltInCsvStrategies
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csvstrategy.AccountFromRulesMapping
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.AttributeAccountMatch
import com.moneymanager.domain.model.csvstrategy.CompanionTransactionRule
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.LegAssembly
import com.moneymanager.domain.model.csvstrategy.LegGroupRule
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.rules.AccountNaming
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.AssetCodeRules
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.Extraction
import com.moneymanager.domain.model.rules.FeeRule
import com.moneymanager.domain.model.rules.LegRole
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleValue
import com.moneymanager.domain.model.rules.StrategyRule
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

    private val advancedRules =
        listOf(
            StrategyRule(
                conditions = listOf(Condition("Direction", ConditionOp.EQUALS, value = "IN")),
                actions =
                    listOf(
                        RuleAction.SwapValues("Source name", "Target name"),
                        RuleAction.SwapValues("Source currency", "Target currency"),
                        RuleAction.Flip,
                    ),
            ),
            StrategyRule.accounts(
                AccountSide.SOURCE,
                listOf(AccountNaming(value = ValueExpr(listOf("Source currency")), trim = true, name = "Wise: {value}")),
            ),
            StrategyRule.accounts(
                AccountSide.TARGET,
                listOf(AccountNaming(value = ValueExpr(listOf("Target currency")), trim = true, name = "Wise: {value}")),
                conditions =
                    listOf(
                        Condition("Source name", ConditionOp.EQUALS_PATH, otherPath = "Target name"),
                        Condition("Source name", ConditionOp.NOT_BLANK),
                    ),
            ),
            StrategyRule.accounts(AccountSide.TARGET, listOf(AccountNaming(value = ValueExpr(listOf("Target name", "Source name"))))),
            StrategyRule(
                conditions = listOf(Condition("Direction", ConditionOp.EQUALS, value = "OUT")),
                actions = listOf(RuleAction.Fee(FeeRule(amount = ValueExpr(listOf("Source fee amount"))))),
            ),
            StrategyRule(actions = listOf(RuleAction.Attribute("wise-id", RuleValue.read("ID"), uniqueIdentifier = true))),
        )

    /** A strategy exercising every advanced mapping type and a rule of each common kind. */
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
                                AccountFromRulesMapping(TransferField.SOURCE_ACCOUNT),
                            TransferField.TARGET_ACCOUNT to
                                AccountFromRulesMapping(TransferField.TARGET_ACCOUNT),
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
                                ),
                            TransferField.CURRENCY to
                                CurrencyLookupMapping(fieldType = TransferField.CURRENCY, value = ValueExpr(listOf("Source currency"))),
                            TransferField.TIMEZONE to
                                HardCodedTimezoneMapping(TransferField.TIMEZONE, "Europe/London"),
                        ),
                    rules = advancedRules,
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
        assertEquals(original.config.rules, rebuilt.config.rules, "rules keep their order and content")
        assertEquals(original.config.companionTransactionRules, rebuilt.config.companionTransactionRules)
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
                                    AccountFromRulesMapping(TransferField.SOURCE_ACCOUNT),
                                TransferField.TARGET_ACCOUNT to
                                    AccountFromRulesMapping(TransferField.TARGET_ACCOUNT),
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
                        rules =
                            listOf(
                                StrategyRule.accounts(
                                    AccountSide.SOURCE,
                                    listOf(
                                        AccountNaming(value = ValueExpr(listOf("Source currency")), trim = true, name = "Wise: {value}"),
                                    ),
                                ),
                                StrategyRule.accounts(
                                    AccountSide.TARGET,
                                    listOf(
                                        AccountNaming(
                                            value = ValueExpr(listOf("Target name")),
                                            trim = true,
                                            attributeTypeName = "card-last4",
                                        ),
                                        AccountNaming(value = ValueExpr(listOf("Target name")), trim = true),
                                    ),
                                ),
                            ),
                    ),
                createdAt = timestamp,
                updatedAt = timestamp,
            )
        val availableColumns = columns.map { it.originalName }.toSet()

        val state = CsvStrategyEditorState(original, availableColumns)
        val rebuilt = buildStrategyFromEditorState(state, original.id, original.createdAt, original.updatedAt)

        assertEquals(original.config.fieldMappings, rebuilt.config.fieldMappings)
        assertEquals(original.config.fundingAttributeMatch, rebuilt.config.fundingAttributeMatch)
        assertEquals(original.config.rules, rebuilt.config.rules)
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
                                    name = "conversion",
                                    key =
                                        listOf(
                                            ValueExpr(listOf("Direction"), Extraction("(?i)^(.*)_(?:debited|credited)$", "$1")),
                                            ValueExpr(listOf("Reference")),
                                        ),
                                    windowSeconds = 60,
                                    assembly = LegAssembly.ThroughAccount(relationshipTypeName = "conversion"),
                                    reconcileWindowSeconds = 5,
                                ),
                                LegGroupRule(name = "trade", windowSeconds = 1, assembly = LegAssembly.Trade("Swap {from} for {to}")),
                            ),
                        rules =
                            advancedStrategy().config.rules +
                                listOf(
                                    StrategyRule(
                                        conditions = listOf(Condition("Direction", ConditionOp.MATCHES, "(?i)_debited$")),
                                        actions = listOf(RuleAction.Leg("conversion", LegRole.Debit)),
                                    ),
                                    StrategyRule(
                                        conditions = listOf(Condition("Direction", ConditionOp.MATCHES, "(?i)_(debited|credited)$")),
                                        actions = listOf(RuleAction.Leg("conversion", LegRole.Credit)),
                                    ),
                                    StrategyRule.accounts(
                                        AccountSide.INTERMEDIATE,
                                        listOf(AccountNaming(value = ValueExpr(listOf("Direction")), name = "Dust")),
                                        conditions = listOf(Condition("Source currency", ConditionOp.MATCHES, "(?i)^DUST$")),
                                    ),
                                    StrategyRule.accounts(
                                        AccountSide.INTERMEDIATE,
                                        listOf(AccountNaming(value = ValueExpr(listOf("Direction")), name = "Crypto.com Conversions")),
                                    ),
                                    StrategyRule(
                                        conditions = listOf(Condition("Direction", ConditionOp.EQUALS, "TRADE")),
                                        actions = listOf(RuleAction.Leg("trade", LegRole.BySign("Source amount (after fees)"))),
                                    ),
                                ),
                    ),
            )
        val availableColumns = columns.map { it.originalName }.toSet()

        val state = CsvStrategyEditorState(original, availableColumns)
        val rebuilt = buildStrategyFromEditorState(state, original.id, original.createdAt, original.updatedAt)

        assertEquals(original.config.rules, rebuilt.config.rules)
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
                                        AccountFromRulesMapping(TransferField.SOURCE_ACCOUNT, defaultCategoryId = 41L),
                                    TransferField.TARGET_ACCOUNT to
                                        AccountFromRulesMapping(TransferField.TARGET_ACCOUNT, defaultCategoryId = 42L),
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

    /** The same, for the category new target accounts land in. */
    @Test
    fun `default category survives on the target account`() {
        val availableColumns = columns.map { it.originalName }.toSet()
        for (categoryId in listOf(7L, 8L)) {
            val target = AccountFromRulesMapping(TransferField.TARGET_ACCOUNT, defaultCategoryId = categoryId)
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

    /** Rules are carried verbatim: a rule naming a column this file lacks simply doesn't fire on it. */
    @Test
    fun `rules naming a column the file lacks are kept on extract`() {
        val original = advancedStrategy()
        val availableColumns = original.config.identificationColumns + setOf("Reference", "Source amount (after fees)")

        val state = CsvStrategyEditorState(original, availableColumns)

        assertEquals(original.config.rules, state.rules)
    }
}

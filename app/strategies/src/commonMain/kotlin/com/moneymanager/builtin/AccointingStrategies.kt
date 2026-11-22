package com.moneymanager.builtin

import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.LegAssembly
import com.moneymanager.domain.model.csvstrategy.LegGroupRule
import com.moneymanager.domain.model.csvstrategy.ReconciliationConfig
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.rules.AccountNaming
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.Extraction
import com.moneymanager.domain.model.rules.FeeRule
import com.moneymanager.domain.model.rules.LegRole
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleValue
import com.moneymanager.domain.model.rules.StrategyRule
import com.moneymanager.domain.model.rules.ValueExpr
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Built-in Accointing reconciliation-source CSV strategy. */
object AccointingStrategies {
    private val accointingCsvStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000016")

    /** Accointing wallet accounts are named with this prefix; see [buildAccointingCsvStrategy]. */
    private const val ACCOINTING_WALLET_PREFIX = "Accointing · "

    private const val ACCOINTING_PAIR_COLUMN = "associatedTransferId"

    private const val ACCOINTING_PAIR_GROUP = "transfer pair"

    /** A deposit's relationship to the withdrawal that funded it: one internal transfer, exported as two rows. */
    private const val ACCOINTING_PAIR_RELATIONSHIP = "transfer pair"

    /**
     * The account candidates of an Accointing row: its source is always its wallet, and so is an order's target.
     * The export doesn't name the other side of a deposit or a withdrawal, so its classification ("income",
     * "internal", …) does.
     */
    private fun accointingAccounts(): List<StrategyRule> {
        val wallet = naming("walletName", name = ACCOINTING_WALLET_PREFIX + AccountNaming.VALUE_PLACEHOLDER, trim = true)
        return listOf(
            accounts(AccountSide.SOURCE, wallet),
            accounts(
                AccountSide.TARGET,
                wallet,
                conditions =
                    listOf(
                        Condition("type", ConditionOp.NOT_EQUALS, value = "deposit"),
                        Condition("type", ConditionOp.NOT_EQUALS, value = "withdraw"),
                    ),
            ),
            accounts(
                AccountSide.TARGET,
                fixedNaming("type", "Accointing: External"),
                conditions = listOf(Condition("classification", ConditionOp.BLANK)),
            ),
            accounts(AccountSide.TARGET, naming("classification", pattern = "^(.+)$", name = "Accointing: $1")),
        )
    }

    /** An asset Accointing doesn't know has the ticker `???` and only a name of its own, so two of them stay apart. */
    private fun accointingNamedAsset(
        column: String,
        nameColumn: String = "temporaryCurrencyName",
    ): StrategyRule =
        rule(
            RuleAction.SetValue(column, RuleValue.read(nameColumn)),
            conditions =
                listOf(
                    Condition(column, ConditionOp.EQUALS, value = "???"),
                    Condition(nameColumn, ConditionOp.NOT_BLANK),
                ),
        )

    /**
     * Accointing's transaction history export (`history.xlsx`). Accointing, like Koinly, is a crypto-tax platform
     * holding its own copy of every crypto movement, so this strategy is a **reconciliation source**: its rows land
     * in shadow accounts ("Accointing · Binance", "Accointing: income", "Accointing Fees") that never count towards
     * real balances, and the Reconciliation tab compares them against the real accounts the wallets are linked to.
     *
     * One row per movement on one wallet, typed by `type`: a deposit fills only the bought columns, a withdrawal only
     * the sold ones, an order both. A transfer between two wallets is therefore two rows, naming each other in
     * `associatedTransferId`. Times are UTC.
     *
     * Its upload template (`transactionType`, `inBuyAmount`, …) isn't covered: it is what a person feeds Accointing,
     * names no wallet, and comes back in this export.
     */
    fun buildAccointingCsvStrategy(now: Instant): CsvImportStrategy {
        val fieldMappings =
            mapOf(
                accountFromRules(TransferField.SOURCE_ACCOUNT),
                accountFromRules(TransferField.TARGET_ACCOUNT),
                TransferField.TIMESTAMP to
                    DateTimeParsingMapping(
                        fieldType = TransferField.TIMESTAMP,
                        dateColumnName = "timeExecuted",
                        dateFormat = "d/M/yyyy",
                        dateTimeFormat = "d/M/yyyy HH:mm:ss",
                    ),
                TransferField.DESCRIPTION to
                    DirectColumnMapping(
                        fieldType = TransferField.DESCRIPTION,
                        value = ValueExpr(listOf("comments", "classification", "type")),
                    ),
                TransferField.AMOUNT to
                    AmountParsingMapping(
                        fieldType = TransferField.AMOUNT,
                        mode = AmountMode.SINGLE_COLUMN,
                        amountColumnName = "soldQuantity",
                    ),
                TransferField.CURRENCY to
                    CurrencyLookupMapping(fieldType = TransferField.CURRENCY, value = ValueExpr(listOf("soldCurrency"))),
                TransferField.TO_AMOUNT to
                    AmountParsingMapping(
                        fieldType = TransferField.TO_AMOUNT,
                        mode = AmountMode.SINGLE_COLUMN,
                        amountColumnName = "boughtQuantity",
                    ),
                TransferField.TO_CURRENCY to
                    CurrencyLookupMapping(fieldType = TransferField.TO_CURRENCY, value = ValueExpr(listOf("boughtCurrency"))),
                TransferField.TIMEZONE to
                    HardCodedTimezoneMapping(fieldType = TransferField.TIMEZONE, timezoneId = "UTC"),
            )
        return CsvImportStrategy(
            id = CsvImportStrategyId(accointingCsvStrategyId),
            name = "Accointing",
            config =
                CsvStrategyConfig(
                    identificationColumns = ACCOINTING_COLUMNS,
                    optionalColumns = ACCOINTING_OPTIONAL_COLUMNS,
                    legGroups =
                        listOf(
                            LegGroupRule(
                                name = ACCOINTING_PAIR_GROUP,
                                key = listOf(ValueExpr(listOf(ACCOINTING_PAIR_COLUMN))),
                                assembly =
                                    LegAssembly.Linked(
                                        relationshipTypeName = ACCOINTING_PAIR_RELATIONSHIP,
                                        anchorWhen = listOf(Condition("type", ConditionOp.EQUALS, value = "withdraw")),
                                    ),
                            ),
                        ),
                    fieldMappings = fieldMappings,
                    rules =
                        listOf(
                            // An order of an asset for the same amount of itself moves nothing.
                            rule(
                                RuleAction.Skip,
                                conditions =
                                    listOf(
                                        Condition("type", ConditionOp.EQUALS, value = "order"),
                                        Condition("boughtCurrency", ConditionOp.EQUALS_PATH, otherPath = "soldCurrency"),
                                        Condition("boughtQuantity", ConditionOp.EQUALS_PATH, otherPath = "soldQuantity"),
                                    ),
                            ),
                            attribute("id", "accointing-id", unique = true),
                            attribute("type", "accointing-type"),
                            attribute("classification", "accointing-classification"),
                            attribute("txId", "accointing-tx-hash"),
                            // The cell shows no time at midnight, as its format hides the time; every other row has one.
                            rule(
                                RuleAction.SetValue(
                                    "timeExecuted",
                                    RuleValue.read(
                                        "timeExecuted",
                                        extraction = Extraction(pattern = "^\\S+$", outputTemplate = "$0 00:00:00"),
                                    ),
                                ),
                            ),
                            accointingNamedAsset("boughtCurrency"),
                            accointingNamedAsset("soldCurrency"),
                            accointingNamedAsset("feeCurrency", nameColumn = "temporaryFeeCurrencyName"),
                            // A pair's two rows name each other, so both are keyed by the withdrawal's id.
                            rule(
                                RuleAction.SetValue(ACCOINTING_PAIR_COLUMN, RuleValue.read("id")),
                                conditions =
                                    listOf(
                                        Condition("type", ConditionOp.EQUALS, value = "withdraw"),
                                        Condition(ACCOINTING_PAIR_COLUMN, ConditionOp.NOT_BLANK),
                                    ),
                            ),
                            rule(
                                RuleAction.Leg(ACCOINTING_PAIR_GROUP, LegRole.Member),
                                conditions = listOf(Condition(ACCOINTING_PAIR_COLUMN, ConditionOp.NOT_BLANK)),
                            ),
                            // A withdrawal of nothing but its fee is that movement, not an empty one with a fee beside it.
                            rule(
                                RuleAction.SetValue("soldQuantity", RuleValue.read("feeQuantity")),
                                RuleAction.SetValue("feeQuantity", RuleValue.constant("0")),
                                conditions =
                                    listOf(
                                        Condition("type", ConditionOp.EQUALS, value = "withdraw"),
                                        Condition("soldQuantity", ConditionOp.EQUALS, value = "0"),
                                    ),
                            ),
                            // A row ignored in Accointing is kept but excluded, like a row deleted in Koinly.
                            excludeWhen("classification", "^ignored$", "ignored in Accointing"),
                            // Sold always holds the primary leg: a deposit's amount and asset move there, so the bought
                            // columns only ever carry an order's credited leg, and the flip puts the money into the wallet.
                            rule(
                                RuleAction.SwapValues("soldQuantity", "boughtQuantity"),
                                RuleAction.SwapValues("soldCurrency", "boughtCurrency"),
                                RuleAction.Flip,
                                conditions = listOf(Condition("type", ConditionOp.EQUALS, value = "deposit")),
                            ),
                        ) + accointingAccounts() +
                            fee(FeeRule(amount = ValueExpr(listOf("feeQuantity")), currency = ValueExpr(listOf("feeCurrency")))),
                    reconciliation = ReconciliationConfig(sourceName = "Accointing", linkableAccountPrefix = ACCOINTING_WALLET_PREFIX),
                ),
            createdAt = now,
            updatedAt = now,
        )
    }

    /** Only Accointing's newer export has this column. */
    private val ACCOINTING_OPTIONAL_COLUMNS = setOf("providerId")

    private val ACCOINTING_COLUMNS =
        setOf(
            "timeExecuted",
            "type",
            "boughtQuantity",
            "boughtCurrency",
            "boughtCurrencyId",
            "soldQuantity",
            "soldCurrency",
            "soldCurrencyId",
            "feeQuantity",
            "feeCurrency",
            "feeCurrencyId",
            "classification",
            "walletName",
            "walletProvider",
            "txId",
            "primaryAddress",
            "otherAddress",
            "temporaryCurrencyName",
            "temporaryFeeCurrencyName",
            "temporaryBoughtCurrencyTicker",
            "temporarySoldCurrencyTicker",
            "temporaryFeeCurrencyTicker",
            "id",
            "associatedTransferId",
            "comments",
        )
}

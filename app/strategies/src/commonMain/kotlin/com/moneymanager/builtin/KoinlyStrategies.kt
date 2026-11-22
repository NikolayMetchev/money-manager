package com.moneymanager.builtin

import com.moneymanager.builtin.BuiltInCsvStrategies.koinlyCsvStrategyId
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
import com.moneymanager.domain.model.rules.ValueExpr
import kotlin.time.Instant

/** Built-in Koinly reconciliation-source CSV strategy. */
object KoinlyStrategies {
    /** Koinly wallet accounts are named with this prefix; see [buildKoinlyCsvStrategy]. */
    private const val KOINLY_WALLET_PREFIX = "Koinly · "

    /** Koinly cells for wallets and assets read `Name;koinly-id` (e.g. `Binance;binance`, `BTC;1`). */
    private val KOINLY_NAME_EXTRACTION = Extraction(pattern = "^([^;]*)", outputTemplate = "$1")

    /**
     * The account candidates for one [side] of a Koinly row: its wallet when the row names one, otherwise the
     * counterparty of a deposit/withdrawal, which names only the user's own wallet — the row's Tag
     * ("reward", "lending_interest", …) when it has one, otherwise a generic external account.
     */
    private fun koinlySide(
        side: AccountSide,
        walletColumn: String,
    ) = listOf(
        accounts(
            side,
            AccountNaming(
                value = ValueExpr.of(walletColumn, extraction = KOINLY_NAME_EXTRACTION),
                trim = true,
                name = KOINLY_WALLET_PREFIX + AccountNaming.VALUE_PLACEHOLDER,
            ),
            conditions = listOf(Condition(walletColumn, ConditionOp.NOT_BLANK)),
        ),
        accounts(side, fixedNaming("Type", "Koinly: External"), naming("Type"), conditions = listOf(Condition("Tag", ConditionOp.BLANK))),
        accounts(side, naming("Tag", pattern = "^(.+)$", name = "Koinly: $1", fallbackName = "Koinly: Other"), naming("Tag")),
    )

    /**
     * Koinly's full transaction export ("Export → Transactions (CSV)"). Koinly is a crypto-tax platform
     * holding its own copy of every crypto movement, so this strategy is a **reconciliation source**: its
     * rows land in shadow accounts ("Koinly · Binance", "Koinly: reward", "Koinly Fees") that never count
     * towards real balances, and the Reconciliation tab compares them against the real accounts the
     * wallets are linked to.
     *
     * One row per movement, typed by `Type`: a deposit fills only the To side, a withdrawal only the From
     * side, a transfer both (two of the user's wallets), a trade both with the same wallet and different
     * assets. A preprocessing swap moves a deposit's amount/asset/wallet into the From columns so From always
     * holds the primary leg and the row's own wallet; the To columns then only ever carry a trade's credited
     * leg.
     */
    fun buildKoinlyCsvStrategy(now: Instant): CsvImportStrategy {
        val fieldMappings =
            mapOf(
                accountFromRules(TransferField.SOURCE_ACCOUNT),
                accountFromRules(TransferField.TARGET_ACCOUNT),
                TransferField.TIMESTAMP to
                    DateTimeParsingMapping(
                        fieldType = TransferField.TIMESTAMP,
                        dateColumnName = "Date (UTC)",
                        dateFormat = "yyyy-MM-dd",
                        dateTimeFormat = "yyyy-MM-dd HH:mm:ss",
                    ),
                TransferField.DESCRIPTION to
                    // A row with a method (an approval, a claim, a swap) rarely has a Description, so the method says what happened.
                    DirectColumnMapping(
                        fieldType = TransferField.DESCRIPTION,
                        value = ValueExpr(listOf("Description", "Method Name (read-only)", "Tag", "Type")),
                    ),
                TransferField.AMOUNT to
                    AmountParsingMapping(
                        fieldType = TransferField.AMOUNT,
                        mode = AmountMode.SINGLE_COLUMN,
                        amountColumnName = "From Amount",
                    ),
                TransferField.CURRENCY to
                    CurrencyLookupMapping(
                        fieldType = TransferField.CURRENCY,
                        value = ValueExpr(listOf("From Currency"), extraction = KOINLY_NAME_EXTRACTION),
                    ),
                TransferField.TO_AMOUNT to
                    AmountParsingMapping(
                        fieldType = TransferField.TO_AMOUNT,
                        mode = AmountMode.SINGLE_COLUMN,
                        amountColumnName = "To Amount",
                    ),
                TransferField.TO_CURRENCY to
                    CurrencyLookupMapping(
                        fieldType = TransferField.TO_CURRENCY,
                        value = ValueExpr(listOf("To Currency"), extraction = KOINLY_NAME_EXTRACTION),
                    ),
                TransferField.TIMEZONE to
                    HardCodedTimezoneMapping(fieldType = TransferField.TIMEZONE, timezoneId = "UTC"),
            )
        return CsvImportStrategy(
            id = CsvImportStrategyId(koinlyCsvStrategyId),
            name = "Koinly",
            config =
                CsvStrategyConfig(
                    identificationColumns = KOINLY_COLUMNS,
                    optionalColumns = KOINLY_OPTIONAL_COLUMNS,
                    legGroups =
                        listOf(
                            LegGroupRule(
                                name = KOINLY_FAMILY_GROUP,
                                key = listOf(ValueExpr(listOf("Parent ID (read-only)", "ID (read-only)"))),
                                assembly =
                                    LegAssembly.Linked(
                                        relationshipTypeName = KOINLY_PARENT_RELATIONSHIP,
                                        anchorWhen = listOf(Condition("Parent ID (read-only)", ConditionOp.BLANK)),
                                    ),
                            ),
                        ),
                    fieldMappings = fieldMappings,
                    rules =
                        listOf(
                            attribute("ID (read-only)", "koinly-id", unique = true),
                            attribute("Type", "koinly-type"),
                            attribute("Tag", "koinly-tag"),
                            attribute("TxHash", "koinly-tx-hash"),
                            // Only Koinly's newer export has these two columns.
                            attribute(
                                "Parent ID (read-only)",
                                "koinly-parent-id",
                                conditions = listOf(Condition("Parent ID (read-only)", ConditionOp.EXISTS)),
                            ),
                            attribute(
                                "Method Name (read-only)",
                                "koinly-method",
                                conditions = listOf(Condition("Method Name (read-only)", ConditionOp.EXISTS)),
                            ),
                            // A child row (a staking reward) is in its parent's group by its Parent ID, a parent by its own ID.
                            rule(
                                RuleAction.Leg(KOINLY_FAMILY_GROUP, LegRole.Member),
                                conditions = listOf(Condition("Parent ID (read-only)", ConditionOp.EXISTS)),
                            ),
                            // A row deleted in Koinly is kept but excluded, like a declined card payment.
                            excludeWhen("Deleted", "^true$", "deleted in Koinly"),
                            // From always holds the primary leg and the row's own wallet: a deposit's amount, asset and
                            // wallet move there, so the To columns only ever carry a trade's credited leg, and the flip
                            // puts the money back into the wallet.
                            rule(
                                RuleAction.SwapValues("From Amount", "To Amount"),
                                RuleAction.SwapValues("From Currency", "To Currency"),
                                RuleAction.SwapValues("From Wallet (read-only)", "To Wallet (read-only)"),
                                RuleAction.Flip,
                                conditions = listOf(Condition("Type", ConditionOp.EQUALS, value = "deposit")),
                            ),
                        ) + koinlySide(AccountSide.SOURCE, "From Wallet (read-only)") +
                            koinlySide(AccountSide.TARGET, "To Wallet (read-only)") +
                            fee(
                                FeeRule(
                                    amount = ValueExpr(listOf("Fee Amount")),
                                    currency = ValueExpr(listOf("Fee Currency"), KOINLY_NAME_EXTRACTION),
                                ),
                            ),
                    reconciliation = ReconciliationConfig(sourceName = "Koinly", linkableAccountPrefix = KOINLY_WALLET_PREFIX),
                ),
            createdAt = now,
            updatedAt = now,
        )
    }

    private const val KOINLY_FAMILY_GROUP = "family"

    /** A row's relationship to the row it came from: the row is the primary member, its parent the related one. */
    private const val KOINLY_PARENT_RELATIONSHIP = "parent"

    /** Columns only Koinly's newer export has: a row's parent row, and the on-chain method behind it. */
    private val KOINLY_OPTIONAL_COLUMNS = setOf("Parent ID (read-only)", "Method Name (read-only)")

    private val KOINLY_COLUMNS =
        setOf(
            "ID (read-only)",
            "Date (UTC)",
            "Type",
            "Tag",
            "From Wallet (read-only)",
            "From Wallet ID",
            "From Amount",
            "From Currency",
            "To Wallet (read-only)",
            "To Wallet ID",
            "To Amount",
            "To Currency",
            "Fee Amount",
            "Fee Currency",
            "Net Worth Amount",
            "Net Worth Currency",
            "Fee Worth Amount",
            "Fee Worth Currency",
            "Net Value (read-only)",
            "Fee Value (read-only)",
            "Value Currency (read-only)",
            "Deleted",
            "From Source (read-only)",
            "To Source (read-only)",
            "Negative Balances (read-only)",
            "Missing Rates (read-only)",
            "Missing Cost Basis (read-only)",
            "Synced To Accounting At (UTC read-only)",
            "TxSrc",
            "TxDest",
            "TxHash",
            "Description",
        )
}

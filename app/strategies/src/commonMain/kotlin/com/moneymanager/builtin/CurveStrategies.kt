package com.moneymanager.builtin

import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.AttributeAccountMatch
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.rules.AccountNaming
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleValue
import com.moneymanager.domain.model.rules.SettlementWindow
import com.moneymanager.domain.model.rules.StrategyRule
import com.moneymanager.domain.model.rules.ValueExpr
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Built-in Curve CSV strategy (a conduit: every card payment passes through it). */
object CurveStrategies {
    private val curveCsvStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000a")

    /**
     * The Curve conduit account. Matches [com.moneymanager.builtin.BuiltInPassThroughs.curve]'s
     * conduitAccountName, so a Curve CSV row's chain (funding card -> Curve -> merchant) runs through the
     * same account as the chain the pass-through detector builds from an underlying card's "CRV*<merchant>"
     * row, letting cross-source reconciliation link the two. Every payment passes through, so it nets to zero.
     */
    private const val CURVE_CONDUIT_ACCOUNT = "Curve"

    /**
     * Names the placeholder that funds a Curve row: the card it names by its last four digits, which the
     * export can't identify further. The engine reconciles it against that card's own statement where the
     * card's import booked the same charge into Curve.
     */
    private fun curveFundingCards(): Array<AccountNaming> =
        arrayOf(
            naming("Card Last 4 Digits", trim = true, pattern = "^(\\S+)$", name = "$CURVE_FUNDING_CARD_PREFIX $1", unidentified = true),
            naming("Card Last 4 Digits", name = CURVE_FUNDING_CARD_PREFIX, unidentified = true),
        )

    /** Prefix of the per-card placeholder accounts funding Curve rows ("Curve card 7721"). */
    private const val CURVE_FUNDING_CARD_PREFIX = "Curve card"

    /**
     * Cross-source reconciliation window for the Curve CSV strategy. Curve's export stamps only a
     * Created Date (no time), and a card authorisation can settle a day or two later, so allow 48h —
     * still gated on an exact same-merchant-account + same amount + same currency match.
     */
    private const val CURVE_RECONCILE_WINDOW_SECONDS = 172_800L

    /** Curve Cash, Curve's rewards balance, held in points ([CURVE_CASH_ASSET]) rather than money. */
    private const val CURVE_CASH_ACCOUNT = "Curve Cash"

    /** Where the Curve Cash points earned on a purchase come from. */
    private const val CURVE_CASHBACK_ACCOUNT = "Curve Cashback"

    /**
     * Money Manager's code for Curve Cash points. Curve's export calls them `CPT`, which the crypto catalog
     * already uses for an unrelated token, so a rule rewrites the export's code to this one.
     */
    private const val CURVE_CASH_ASSET = "CURVECASH"

    /**
     * Built-in strategy for Curve's transaction exports: the older "Transaction History <date>.csv" and the
     * newer "Transactions <dates>.csv" that replaced it. Curve is a card aggregator: a Curve payment is
     * forwarded to an underlying card, so each Curve row is the merchant-facing side of a movement whose
     * funding side lives on the underlying card's statement.
     *
     * The two exports share no column, so the rules first gather what the mappings read under one name each
     * (`Date`, `Time`, `Merchant`, `Amount`, `Currency`, `Card Last 4 Digits`) from whichever column the file
     * has, and nothing identifies a file by its columns alone: every column is optional, and the file name
     * (`Transaction History…` or `Transactions…`) picks the strategy.
     *
     * Every row but a Curve Cash one runs through [CURVE_CONDUIT_ACCOUNT] (the strategy's conduit) as the chain
     * `Curve card <last 4> -> Curve -> merchant`, so Curve always nets to zero. The funding side is a
     * placeholder per card. Where the card's own import booked the charge into Curve (its "CRV*<merchant>"
     * pass-through, see [com.moneymanager.builtin.BuiltInPassThroughs.curve]), the two reconcile — by the
     * card's `card-last4` attribute, or by the card's movement into Curve alone — and, in either import
     * order, the card's record of the funding stays counted while this row's merchant leg, naming the real
     * merchant, supersedes the card's guess at it.
     *
     * **Older export.** Its first column has a BLANK header (a running row index), which is otherwise unused.
     * Amounts are the merchant-side amount and currency, always positive spends out of Curve, so there is
     * deliberately no flip-on-positive. A foreign-currency row can't match the card's funding leg, so it stays
     * funded by the placeholder, in its own currency.
     *
     * **Newer export.** It gives the amount the funding card was charged (`Txn Amount (Funding Card)`, which
     * includes any Curve fee) alongside the merchant-side foreign spend, so the spend is booked in what the
     * card paid: that is what the card statement's funding leg holds, foreign purchases included. The foreign
     * spend and the fee are kept as attributes. Curve Cash rows have no card. Their amounts are points (`CPT`,
     * booked as [CURVE_CASH_ASSET]):
     *  - a row with no Type is points earned on a purchase: [CURVE_CASHBACK_ACCOUNT] -> [CURVE_CASH_ACCOUNT];
     *  - a row paid with the "Curve Cash" card spends points: [CURVE_CASH_ACCOUNT] -> merchant.
     */
    fun buildCurveCsvStrategy(now: Instant): CsvImportStrategy {
        val earned = listOf(Condition("Card Name", ConditionOp.EQUALS, "Curve Cash"), Condition("Type", ConditionOp.BLANK))
        val curveCash = Condition("Card Name", ConditionOp.EQUALS, "Curve Cash")
        val fieldMappings =
            mapOf(
                accountFromRules(TransferField.SOURCE_ACCOUNT),
                accountFromRules(TransferField.TARGET_ACCOUNT),
                TransferField.TIMESTAMP to
                    DateTimeParsingMapping(
                        fieldType = TransferField.TIMESTAMP,
                        dateColumnName = "Date",
                        dateFormat = "yyyy-MM-dd",
                        timeColumnName = "Time",
                        timeFormat = "HH:mm:ss",
                    ),
                TransferField.DESCRIPTION to
                    DirectColumnMapping(fieldType = TransferField.DESCRIPTION, value = ValueExpr(listOf("Merchant"))),
                TransferField.AMOUNT to
                    AmountParsingMapping(
                        fieldType = TransferField.AMOUNT,
                        mode = AmountMode.SINGLE_COLUMN,
                        amountColumnName = "Amount",
                    ),
                TransferField.CURRENCY to
                    CurrencyLookupMapping(fieldType = TransferField.CURRENCY, value = ValueExpr(listOf("Currency"))),
                TransferField.TIMEZONE to HardCodedTimezoneMapping(fieldType = TransferField.TIMEZONE, timezoneId = "UTC"),
            )
        val rules =
            listOf(
                // One name for what each export keeps under its own column. The older export has no time, so
                // its rows take the default one.
                setFrom("Date", "Date (YYYY-MM-DD as UTC)", "Created Date"),
                setFrom("Time", "Time (HH:MM:SS as UTC)"),
                setFrom("Merchant", "Merchant", "Merchant Name"),
                setFrom("Amount", "Txn Amount (Funding Card)", "Txn Amount"),
                setFrom("Currency", "Txn Currency (Funding Card)", "Txn Currency"),
                // CPT is Curve's own name for its points here, so this is no alias: other sources' CPT is another asset.
                rule(
                    RuleAction.SetValue("Currency", RuleValue.constant(CURVE_CASH_ASSET)),
                    conditions = listOf(Condition("Currency", ConditionOp.EQUALS, "CPT")),
                ),
                setFrom("Card Last 4 Digits", "Card Last 4 Digits", "Funding Card Last 4 Digits"),
                attribute("Card Last 4 Digits", "curve-funding-card"),
                attribute("Merchant MCC Code", "curve-mcc"),
                attribute("Card Name", "curve-card-name"),
                attribute("Category", "curve-category"),
                attribute("Fees", "curve-fee"),
                attribute("Txn Amount (Foreign Spend)", "curve-foreign-amount"),
                attribute("Txn Currency (Foreign Spend)", "curve-foreign-currency"),
                attribute("Notes", "curve-notes"),
                accounts(AccountSide.SOURCE, fixedNaming("Merchant", CURVE_CASHBACK_ACCOUNT), conditions = earned),
                accounts(AccountSide.SOURCE, fixedNaming("Merchant", CURVE_CASH_ACCOUNT), conditions = listOf(curveCash)),
                accounts(AccountSide.SOURCE, *curveFundingCards()),
                accounts(AccountSide.TARGET, fixedNaming("Merchant", CURVE_CASH_ACCOUNT), conditions = earned),
                // Merchant account, looked up/created from the clean merchant name; persisted account mappings
                // apply, so a user can normalise Curve's names onto their own merchant accounts.
                accounts(AccountSide.TARGET, naming("Merchant")),
                rule(
                    RuleAction.ThroughConduit(CURVE_CONDUIT_ACCOUNT),
                    conditions = listOf(Condition("Card Name", ConditionOp.NOT_EQUALS, "Curve Cash")),
                ),
            )
        return CsvImportStrategy(
            id = CsvImportStrategyId(curveCsvStrategyId),
            name = "Curve CSV",
            config =
                CsvStrategyConfig(
                    identificationColumns = emptySet(),
                    optionalColumns = CURVE_COLUMNS,
                    fieldMappings = fieldMappings,
                    rules = rules,
                    // What picks the strategy, as no column does: Curve's column sets are disjoint from every other
                    // strategy's, so only a file named like a Curve export can reach it.
                    fileNamePattern = "^(Transaction History|Transactions)",
                    crossSourceReconcileWindow = SettlementWindow.Elapsed(CURVE_RECONCILE_WINDOW_SECONDS),
                    // Reconcile each spend against the funding leg on the account whose card-last4 attribute
                    // matches this column, so a Curve charge isn't double-counted against the underlying card's
                    // own import.
                    fundingAttributeMatch =
                        AttributeAccountMatch(
                            column = "Card Last 4 Digits",
                            attributeTypeName = WellKnownIds.ACCOUNT_CARD_LAST4_ATTR_TYPE_NAME,
                        ),
                ),
            createdAt = now,
            updatedAt = now,
        )
    }

    /** `column` set to the first non-blank of [from]. */
    private fun setFrom(
        column: String,
        vararg from: String,
    ): StrategyRule = rule(RuleAction.SetValue(column, RuleValue.read(from.first(), *from.drop(1).toTypedArray())))

    /** Every column of Curve's two exports (the older one's first column has a blank header). */
    private val CURVE_COLUMNS =
        setOf(
            // Older "Transaction History" export.
            "",
            "Created Date",
            "Merchant Name",
            "Funding Card Last 4 Digits",
            "Merchant MCC Code",
            "Txn Currency",
            "Txn Amount",
            // Newer "Transactions" export.
            "Export Format",
            "Date (YYYY-MM-DD as UTC)",
            "Time (HH:MM:SS as UTC)",
            "Merchant",
            "Txn Amount (Funding Card)",
            "Txn Currency (Funding Card)",
            "Txn Amount (Foreign Spend)",
            "Txn Currency (Foreign Spend)",
            "Card Name",
            "Card Last 4 Digits",
            "Type",
            "Category",
            "Notes",
            "Fees",
        )
}

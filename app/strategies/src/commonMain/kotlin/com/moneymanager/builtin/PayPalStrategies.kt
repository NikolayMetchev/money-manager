package com.moneymanager.builtin

import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMapping
import com.moneymanager.domain.model.csvstrategy.LegAssembly
import com.moneymanager.domain.model.csvstrategy.LegGroupRule
import com.moneymanager.domain.model.csvstrategy.TimezoneLookupMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.rules.AccountNaming
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.Extraction
import com.moneymanager.domain.model.rules.LegRole
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.SettlementWindow
import com.moneymanager.domain.model.rules.ValueExpr
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Built-in PayPal CSV strategies, modern and legacy exports. */
object PayPalStrategies {
    private val payPalCsvStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000011")

    /**
     * The PayPal account, holding one balance per currency. Matches
     * [com.moneymanager.builtin.BuiltInPassThroughs.payPal]'s conduitAccountName, so the movement a card
     * statement's "PAYPAL *<merchant>" row routes into PayPal and the deposit PayPal's own export records
     * land on the same account — which is what lets the two reconcile.
     */
    private const val PAYPAL_ACCOUNT = "PayPal"

    /**
     * Placeholder counterparty for PayPal's "General Credit Card Deposit"/"Withdrawal" rows: the export
     * says a card funded the payment (or took the refund back) but never which card. Where a card import
     * already booked the same money into PayPal it reconciles away; see `AccountNaming.unidentified`.
     */
    private const val PAYPAL_CARD_FUNDING_ACCOUNT = "PayPal Card Funding"

    /** The rows that move money between a card and PayPal (a regex alternation over the `Type` column). */
    private const val PAYPAL_CARD_FUNDING_TYPES = "General Credit Card Deposit|General Credit Card Withdrawal"

    /** The payment and refund rows a card funding row can be paired with (a regex alternation over `Type`). */
    private const val PAYPAL_CARD_FUNDED_TYPES =
        "Pre-approved Payment Bill User Payment|Express Checkout Payment|Mobile Payment|eBay Auction Payment|" +
            "Website Payment|General Payment|Donation Payment|Payment Refund|Payment Reversal"

    /** Same placeholder role as [PAYPAL_CARD_FUNDING_ACCOUNT], for withdrawals to and deposits from a bank account. */
    private const val PAYPAL_BANK_TRANSFERS_ACCOUNT = "PayPal Bank Transfers"

    /**
     * Suspense counterparty for a currency-conversion leg whose pair did not assemble into a trade. As with
     * [BINANCE_TRADING_ACCOUNT], an empty one is the healthy state.
     */
    private const val PAYPAL_CONVERSIONS_ACCOUNT = "PayPal Conversions"

    /**
     * PayPal's own corrections, which the export attributes to "PayPal" itself (a "Payment Reversal" of a
     * payment that never completed, an "MSB Redemption" credit) — naming the counterparty after the row
     * would make it the PayPal account and a self-transfer.
     */
    private const val PAYPAL_ADJUSTMENTS_ACCOUNT = "PayPal Adjustments"

    /**
     * The abbreviations PayPal writes in its `Time zone` column, as the UTC offset each stands for. An
     * abbreviation names one offset (BST is always +01:00), so this is exact even across a DST change.
     */
    private val payPalTimezones =
        mapOf(
            "GMT" to "UTC",
            "BST" to "+01:00",
            "CET" to "+01:00",
            "CEST" to "+02:00",
            "EST" to "-05:00",
            "EDT" to "-04:00",
            "CST" to "-06:00",
            "CDT" to "-05:00",
            "MST" to "-07:00",
            "MDT" to "-06:00",
            "PST" to "-08:00",
            "PDT" to "-07:00",
            "AEST" to "+10:00",
            "AEDT" to "+11:00",
        )

    /**
     * Cross-source window for the PayPal strategies. A card statement books the charge that funds a
     * PayPal payment anywhere from a few minutes before to about a day and a half after PayPal does
     * (measured against crypto.com card exports), so allow two days. The placeholder rules that do most
     * of PayPal's reconciling match within the engine's date tolerance instead.
     */
    private const val PAYPAL_RECONCILE_WINDOW_SECONDS = 172_800L

    /** The columns every PayPal activity download has, whichever year it is from. */
    private val PAYPAL_COLUMNS =
        setOf("Date", "Time", "Time zone", "Name", "Type", "Status", "Currency", "Amount", "Receipt ID", "Balance")

    /** Columns only the newer export has. */
    private val PAYPAL_OPTIONAL_COLUMNS = setOf("Fees", "Total", "Exchange Rate", "Transaction ID", "Item Title")

    /** PayPal's leg group pairing a conversion's debit and credit rows into one trade. */
    private const val PAYPAL_CONVERSION_GROUP = "conversion"

    /** PayPal's leg group linking a card deposit to the payment it funded (or a refund to its withdrawal). */
    private const val PAYPAL_CARD_FUNDING_GROUP = "card funding"

    /**
     * Built-in strategy for PayPal's activity download (`Download.CSV`, or a date-range name such as
     * `2025-05-04-2026-05-03.CSV`), in both its current 15-column form and the older 10-column one that
     * lacks [PAYPAL_OPTIONAL_COLUMNS]. See [payPalStrategyConfig].
     */
    fun buildPayPalCsvStrategy(now: Instant): CsvImportStrategy =
        CsvImportStrategy(
            id = CsvImportStrategyId(payPalCsvStrategyId),
            name = "PayPal CSV",
            config =
                payPalStrategyConfig(),
            createdAt = now,
            updatedAt = now,
        )

    /**
     * What every PayPal export shares. Every row moves money into or out of the one [PAYPAL_ACCOUNT]
     * (signed Amount, positive = in), whatever its currency. The counterparty is the row's Name — the
     * merchant or person — except where the export cannot say who it was:
     *
     *  - **Card funding.** A payment funded by a card appears as two rows: the payment to the merchant, and
     *    a "General Credit Card Deposit" of the same amount that names no card. The deposit is booked
     *    against the [PAYPAL_CARD_FUNDING_ACCOUNT] placeholder, so where a card statement already routed the
     *    charge into PayPal (the "PAYPAL *" pass-through), the two reconcile and the card's record — which
     *    names the card — stays counted. The card's pass-through spend leg (PayPal -> its guess at the
     *    merchant, from a truncated descriptor and in the card's currency) is then superseded by PayPal's own
     *    payment row, which names the real payee and the currency actually paid. Either import order works.
     *    Refunds mirror this through "General Credit Card Withdrawal". PayPal is a conduit for these: the
     *    deposit and the payment it funded (stamped the same second, same amount) are linked as one
     *    `pass-through` chain, card funding -> PayPal -> payee, as is a refund and its withdrawal.
     *  - **Bank transfers** go to [PAYPAL_BANK_TRANSFERS_ACCOUNT], reconciling against the bank's own export.
     *  - **Currency conversions** arrive as a debit row and a credit row at the same second and become one
     *    trade on the PayPal account.
     *
     * Rows that are not movements — authorisations, holds, orders, invoices and money requests (a request
     * received leaves the balance untouched; paying it is its own row) — and refused card deposits are imported excluded. Times are local to the PayPal
     * profile, and the `Time zone` column names the zone by abbreviation (GMT/BST), which
     * [payPalTimezones] turns into an offset.
     */
    private fun payPalStrategyConfig(): CsvStrategyConfig<FieldMapping> {
        val fieldMappings =
            mapOf(
                accountFromRules(TransferField.SOURCE_ACCOUNT),
                accountFromRules(TransferField.TARGET_ACCOUNT),
                TransferField.TIMESTAMP to
                    DateTimeParsingMapping(
                        fieldType = TransferField.TIMESTAMP,
                        dateColumnName = "Date",
                        dateFormat = "dd/MM/yyyy",
                        timeColumnName = "Time",
                        timeFormat = "HH:mm:ss",
                    ),
                TransferField.DESCRIPTION to
                    DirectColumnMapping(fieldType = TransferField.DESCRIPTION, value = ValueExpr(listOf("Name", "Type"))),
                TransferField.AMOUNT to
                    AmountParsingMapping(
                        fieldType = TransferField.AMOUNT,
                        mode = AmountMode.SINGLE_COLUMN,
                        amountColumnName = "Amount",
                    ),
                TransferField.CURRENCY to
                    CurrencyLookupMapping(fieldType = TransferField.CURRENCY, value = ValueExpr(listOf("Currency"))),
                TransferField.TIMEZONE to
                    TimezoneLookupMapping(fieldType = TransferField.TIMEZONE, columnName = "Time zone", aliases = payPalTimezones),
            )
        val rules =
            listOf(
                attribute("Type", "paypal-type"),
                attribute("Status", "paypal-status"),
                excludeWhen(
                    "Type",
                    "^(General Authorisation|Void of Authorisation|Account Hold for Open Authorisation|" +
                        "Reversal of General Account Hold|Order|Invoice Received|Request Received)$",
                    "not a movement",
                ),
                excludeWhen("Status", "^Refused$", "refused"),
                attribute("Transaction ID", "paypal-transaction-id"),
                attribute("Item Title", "paypal-item-title"),
                // Per currency, after the row. A hold (imported excluded) lowers it until its reversal.
                balance("Balance"),
                // Informational: Amount is already net of it (it is what moved the balance).
                attribute("Fees", "paypal-fee", extraction = Extraction(pattern = "^.*[1-9].*$")),
                // The older export lists every withdrawal to a bank twice: the row that moves the balance (status
                // `Pending`, or `Removed`) and, seconds later, a `Completed` echo that leaves it where it was. Only
                // that export lacks a Transaction ID. (Two withdrawals in a 2015-2020 export have no earlier twin and
                // are lost to this rule; a file holding only `Removed` rows is unaffected.)
                excludeWhen(
                    "Type",
                    "^General Withdrawal$",
                    "status echo of a withdrawal",
                    conditions =
                        listOf(
                            Condition("Status", ConditionOp.EQUALS, "Completed"),
                            Condition("Transaction ID", ConditionOp.MISSING),
                        ),
                ),
            ) +
                listOf(
                    incomingWhenPositive("Amount"),
                    accounts(AccountSide.SOURCE, fixedNaming("Type", PAYPAL_ACCOUNT), naming("Type")),
                    accounts(
                        AccountSide.TARGET,
                        naming(
                            "Type",
                            pattern = "^General Credit Card (Deposit|Withdrawal)$",
                            name = PAYPAL_CARD_FUNDING_ACCOUNT,
                            unidentified = true,
                        ),
                        naming(
                            "Type",
                            pattern =
                                "^(General Withdrawal|User Initiated Withdrawal|Bank deposit to PayPal account|" +
                                    "Reversal of ACH Deposit)$",
                            name = PAYPAL_BANK_TRANSFERS_ACCOUNT,
                            unidentified = true,
                        ),
                        // Conversion legs only reach here when their pair did not resolve into a trade.
                        naming("Type", pattern = "^General Currency Conversion$", name = PAYPAL_CONVERSIONS_ACCOUNT),
                        // "PayPal" itself, or one of its entities ("PayPal (Europe) S.a.r.l. et Cie, SCA") — not a
                        // merchant whose name merely starts with it ("PAYPAL ETSY IRELAND").
                        naming("Name", pattern = "^PayPal(?: \\(.*)?$", name = PAYPAL_ADJUSTMENTS_ACCOUNT),
                        // The merchant or person; a row without a Name is named after its Type.
                        AccountNaming(value = ValueExpr.of("Name", "Type"), trim = true),
                    ),
                    rule(
                        RuleAction.Leg(PAYPAL_CONVERSION_GROUP, LegRole.BySign("Amount")),
                        conditions = listOf(Condition("Type", ConditionOp.EQUALS, "General Currency Conversion")),
                    ),
                    rule(
                        RuleAction.Leg(PAYPAL_CARD_FUNDING_GROUP, LegRole.BySign("Amount")),
                        conditions =
                            listOf(Condition("Type", ConditionOp.MATCHES, "^($PAYPAL_CARD_FUNDING_TYPES|$PAYPAL_CARD_FUNDED_TYPES)$")),
                    ),
                )
        return CsvStrategyConfig(
            identificationColumns = PAYPAL_COLUMNS,
            optionalColumns = PAYPAL_OPTIONAL_COLUMNS,
            fieldMappings = fieldMappings,
            rules = rules,
            crossSourceReconcileWindow = SettlementWindow.Elapsed(PAYPAL_RECONCILE_WINDOW_SECONDS),
            legGroups =
                listOf(
                    LegGroupRule(
                        name = PAYPAL_CONVERSION_GROUP,
                        assembly = LegAssembly.Trade(description = "Convert {from} to {to}"),
                    ),
                    LegGroupRule(
                        name = PAYPAL_CARD_FUNDING_GROUP,
                        // Same currency and the same amount, whichever way it moved.
                        key = listOf(ValueExpr(listOf("Currency")), ValueExpr(listOf("Amount"), Extraction("^-?(.*)$", "$1"))),
                        assembly =
                            LegAssembly.ThroughAccount(
                                relationshipTypeName = WellKnownIds.PASS_THROUGH_RELATIONSHIP_TYPE_NAME,
                                fundingWhen = listOf(Condition("Type", ConditionOp.MATCHES, "^($PAYPAL_CARD_FUNDING_TYPES)$")),
                            ),
                    ),
                ),
        )
    }
}

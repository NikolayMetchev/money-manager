package com.moneymanager.builtin

import com.moneymanager.builtin.BuiltInCsvStrategies.freetradeCsvStrategyId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FILE_NAME_COLUMN
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleValue
import com.moneymanager.domain.model.rules.SettlementWindow
import com.moneymanager.domain.model.rules.ValueExpr
import kotlin.time.Instant

/** Built-in Freetrade CSV strategy. */
object FreetradeStrategies {
    private const val FREETRADE_ISA_ACCOUNT = "Freetrade ISA"
    private const val FREETRADE_GIA_ACCOUNT = "Freetrade GIA"
    private const val FREETRADE_TOP_UPS_ACCOUNT = "Freetrade Top Ups"
    private const val FREETRADE_WITHDRAWALS_ACCOUNT = "Freetrade Withdrawals"
    private const val FREETRADE_INTEREST_ACCOUNT = "Freetrade Interest"
    private const val FREETRADE_DIVIDENDS_ACCOUNT = "Freetrade Dividends"
    private const val FREETRADE_FREE_SHARES_ACCOUNT = "Freetrade Free Shares"

    /** A top-up lands the day the bank sends it, so its record on the bank's statement is at most two days away. */
    private const val FREETRADE_RECONCILE_WINDOW_SECONDS = 172_800L

    private const val FREETRADE_TITLE_COLUMN = "Title"
    private const val FREETRADE_TYPE_COLUMN = "Type"
    private const val FREETRADE_TIME_COLUMN = "Timestamp"
    private const val FREETRADE_CURRENCY_COLUMN = "Account Currency"
    private const val FREETRADE_TOTAL_COLUMN = "Total Amount in Account Currency"
    private const val FREETRADE_SIDE_COLUMN = "Buy / Sell"
    private const val FREETRADE_TICKER_COLUMN = "Ticker"
    private const val FREETRADE_ISIN_COLUMN = "ISIN"
    private const val FREETRADE_STAMP_DUTY_COLUMN = "Stamp Duty"
    private const val FREETRADE_QUANTITY_COLUMN = "Quantity"
    private const val FREETRADE_VENUE_COLUMN = "Venue"
    private const val FREETRADE_ORDER_ID_COLUMN = "Order ID"
    private const val FREETRADE_FX_FEE_COLUMN = "FX Fee Amount"
    private const val FREETRADE_WITHHELD_TAX_COLUMN = "Dividend Withheld Tax Amount"

    // Set by the rules on trade rows only: a dividend also names a ticker and a quantity, and must stay a transfer.
    private const val FREETRADE_TRADE_ASSET_COLUMN = "Trade Asset"
    private const val FREETRADE_TRADE_AMOUNT_COLUMN = "Trade Amount"

    // Set by the rules from the file name: the account every row of the file belongs to.
    private const val FREETRADE_ACCOUNT_COLUMN = "Account"

    /** The account type [accountTypes] (a regex alternative) as a word of a file name: `visa.csv` isn't an ISA. */
    private fun freetradeAccountTypePattern(accountTypes: String): String = "(^|[^a-z])($accountTypes)([^a-z]|$)"

    private val freetradeColumns =
        setOf(
            FREETRADE_TITLE_COLUMN,
            FREETRADE_TYPE_COLUMN,
            FREETRADE_TIME_COLUMN,
            FREETRADE_CURRENCY_COLUMN,
            FREETRADE_TOTAL_COLUMN,
            FREETRADE_SIDE_COLUMN,
            FREETRADE_TICKER_COLUMN,
            FREETRADE_ISIN_COLUMN,
            "Price per Share in Account Currency",
            FREETRADE_STAMP_DUTY_COLUMN,
            FREETRADE_QUANTITY_COLUMN,
            FREETRADE_VENUE_COLUMN,
            FREETRADE_ORDER_ID_COLUMN,
            "Order Type",
            "Instrument Currency",
            "Total Amount in Instrument Currency",
            "Price per Share",
            "FX Rate",
            "Base FX Rate",
            "FX Fee (BPS)",
            FREETRADE_FX_FEE_COLUMN,
            "Dividend Ex Date",
            "Dividend Pay Date",
            "Dividend Eligible Quantity",
            "Dividend Amount Per Share",
            "Dividend Gross Distribution Amount",
            "Dividend Net Distribution Amount",
            "Dividend Withheld Tax Percentage",
            FREETRADE_WITHHELD_TAX_COLUMN,
            "Stock Split Ex Date",
            "Stock Split Pay Date",
            "Stock Split New ISIN",
            "Stock Split Rate of Share Outturn From",
            "Stock Split Rate of Share Outturn To",
            "Stock Split Maintain Holding of Initial ISIN",
            "Stock Split New Share Quantity",
            "Stock Split Rate of Cash Outturn Amount",
            "Stock Split Rate of Cash Outturn Currency",
            "Stock Split Cash Outturn Received Amount",
            "Stock Split Has Fractional Payout",
            "Stock Split Rate of Fractional Payout Amount",
            "Stock Split Rate of Fractional Payout Currency",
            "Stock Split Fractional Payout Cash Received Amount",
            "Stock Split Fractional Payout Cash Received Currency",
        )

    /**
     * Freetrade's activity export (`freetrade_ISA_all_activity_…csv`). Each account type exports its own file with the
     * same columns and nothing inside says which it is, so the rules read the account from the file name.
     *
     * Every amount is positive, so `Type` and `Buy / Sell` give the direction. A row of a type this doesn't know names
     * no counterparty, so it fails visibly instead of leaving a balance silently wrong. An order's total already
     * includes its stamp duty and FX fee, so those are only kept as attributes: as fees they would be charged twice.
     * Shares are assets named by their ticker, created on demand. A free share is paid for by
     * [FREETRADE_FREE_SHARES_ACCOUNT], as it costs the account's cash nothing.
     */
    fun buildFreetradeCsvStrategy(now: Instant): CsvImportStrategy =
        CsvImportStrategy(
            id = CsvImportStrategyId(freetradeCsvStrategyId),
            name = "Freetrade",
            config =
                CsvStrategyConfig(
                    identificationColumns = freetradeColumns,
                    fileNamePattern = freetradeAccountTypePattern("ISA|GIA"),
                    // Timestamps are to the millisecond, and two top-ups of one amount in a week are two top-ups.
                    duplicateDateTolerance = SettlementWindow.Elapsed(0),
                    crossSourceReconcileWindow = SettlementWindow.Elapsed(FREETRADE_RECONCILE_WINDOW_SECONDS),
                    fieldMappings =
                        mapOf(
                            accountFromRules(TransferField.SOURCE_ACCOUNT),
                            accountFromRules(TransferField.TARGET_ACCOUNT),
                            TransferField.TIMESTAMP to
                                DateTimeParsingMapping(
                                    fieldType = TransferField.TIMESTAMP,
                                    dateColumnName = FREETRADE_TIME_COLUMN,
                                    dateFormat = "yyyy-MM-dd",
                                    dateTimeFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                                ),
                            TransferField.DESCRIPTION to
                                DirectColumnMapping(
                                    fieldType = TransferField.DESCRIPTION,
                                    value = ValueExpr(listOf(FREETRADE_TITLE_COLUMN)),
                                ),
                            TransferField.AMOUNT to
                                AmountParsingMapping(
                                    fieldType = TransferField.AMOUNT,
                                    mode = AmountMode.SINGLE_COLUMN,
                                    amountColumnName = FREETRADE_TOTAL_COLUMN,
                                ),
                            TransferField.CURRENCY to
                                CurrencyLookupMapping(
                                    fieldType = TransferField.CURRENCY,
                                    value = ValueExpr(listOf(FREETRADE_CURRENCY_COLUMN)),
                                ),
                            TransferField.TO_AMOUNT to
                                AmountParsingMapping(
                                    fieldType = TransferField.TO_AMOUNT,
                                    mode = AmountMode.SINGLE_COLUMN,
                                    amountColumnName = FREETRADE_TRADE_AMOUNT_COLUMN,
                                ),
                            TransferField.TO_CURRENCY to
                                CurrencyLookupMapping(
                                    fieldType = TransferField.TO_CURRENCY,
                                    value = ValueExpr(listOf(FREETRADE_TRADE_ASSET_COLUMN)),
                                ),
                            TransferField.TIMEZONE to
                                HardCodedTimezoneMapping(fieldType = TransferField.TIMEZONE, timezoneId = "UTC"),
                        ),
                    rules =
                        listOf(
                            rule(
                                RuleAction.Skip,
                                conditions = listOf(Condition(FREETRADE_TYPE_COLUMN, ConditionOp.EQUALS, "MONTHLY_STATEMENT")),
                                name = "A statement moves no money",
                            ),
                            rule(
                                RuleAction.SetValue(FREETRADE_ACCOUNT_COLUMN, RuleValue.constant(FREETRADE_ISA_ACCOUNT)),
                                conditions = listOf(Condition(FILE_NAME_COLUMN, ConditionOp.MATCHES, freetradeAccountTypePattern("ISA"))),
                                name = "An ISA export is the ISA account",
                            ),
                            rule(
                                RuleAction.SetValue(FREETRADE_ACCOUNT_COLUMN, RuleValue.constant(FREETRADE_GIA_ACCOUNT)),
                                conditions = listOf(Condition(FILE_NAME_COLUMN, ConditionOp.MATCHES, freetradeAccountTypePattern("GIA"))),
                                name = "A GIA export is the GIA account",
                            ),
                            attribute(FREETRADE_TYPE_COLUMN, "freetrade-type"),
                            attribute(FREETRADE_ORDER_ID_COLUMN, "freetrade-order-id"),
                            attribute(FREETRADE_TICKER_COLUMN, "freetrade-ticker"),
                            attribute(FREETRADE_ISIN_COLUMN, "freetrade-isin"),
                            attribute(FREETRADE_VENUE_COLUMN, "freetrade-venue"),
                            attribute(FREETRADE_STAMP_DUTY_COLUMN, "freetrade-stamp-duty"),
                            attribute(FREETRADE_FX_FEE_COLUMN, "freetrade-fx-fee"),
                            attribute(FREETRADE_WITHHELD_TAX_COLUMN, "freetrade-withheld-tax"),
                            rule(
                                RuleAction.SetValue(FREETRADE_TRADE_ASSET_COLUMN, RuleValue.read(FREETRADE_TICKER_COLUMN)),
                                RuleAction.SetValue(FREETRADE_TRADE_AMOUNT_COLUMN, RuleValue.read(FREETRADE_QUANTITY_COLUMN)),
                                conditions = listOf(Condition(FREETRADE_TYPE_COLUMN, ConditionOp.IN, "ORDER,FREESHARE_ORDER")),
                                name = "An order trades its total for its shares",
                            ),
                            rule(
                                RuleAction.SwapValues(FREETRADE_TOTAL_COLUMN, FREETRADE_TRADE_AMOUNT_COLUMN),
                                RuleAction.SwapValues(FREETRADE_CURRENCY_COLUMN, FREETRADE_TRADE_ASSET_COLUMN),
                                conditions = listOf(Condition(FREETRADE_SIDE_COLUMN, ConditionOp.EQUALS, "SELL")),
                                name = "A sale trades the shares for the total",
                            ),
                            // PROPERTY is a REIT's property income distribution, a dividend in all but name.
                            incomingWhen(Condition(FREETRADE_TYPE_COLUMN, ConditionOp.IN, "TOP_UP,INTEREST_FROM_CASH,DIVIDEND,PROPERTY")),
                            accounts(
                                AccountSide.SOURCE,
                                naming(FREETRADE_TYPE_COLUMN, pattern = "^FREESHARE_ORDER$", name = FREETRADE_FREE_SHARES_ACCOUNT),
                                naming(FREETRADE_ACCOUNT_COLUMN),
                            ),
                            accounts(
                                AccountSide.TARGET,
                                naming(FREETRADE_ACCOUNT_COLUMN),
                                conditions = listOf(Condition(FREETRADE_TYPE_COLUMN, ConditionOp.IN, "ORDER,FREESHARE_ORDER")),
                            ),
                            accounts(
                                AccountSide.TARGET,
                                // The export never says which bank the money came from or went to.
                                naming(FREETRADE_TYPE_COLUMN, pattern = "^TOP_UP$", name = FREETRADE_TOP_UPS_ACCOUNT, unidentified = true),
                                naming(
                                    FREETRADE_TYPE_COLUMN,
                                    pattern = "^WITHDRAWAL$",
                                    name = FREETRADE_WITHDRAWALS_ACCOUNT,
                                    unidentified = true,
                                ),
                                naming(FREETRADE_TYPE_COLUMN, pattern = "^INTEREST_FROM_CASH$", name = FREETRADE_INTEREST_ACCOUNT),
                                naming(FREETRADE_TYPE_COLUMN, pattern = "^(DIVIDEND|PROPERTY)$", name = FREETRADE_DIVIDENDS_ACCOUNT),
                            ),
                        ),
                ),
            createdAt = now,
            updatedAt = now,
        )
}

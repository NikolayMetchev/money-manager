package com.moneymanager.builtin

import com.moneymanager.builtin.BuiltInCsvStrategies.barclaycardCsvStrategyId
import com.moneymanager.builtin.BuiltInCsvStrategies.barclaycardOfxStrategyId
import com.moneymanager.builtin.StatementDefaults.QIF_STATEMENT_RECONCILE_WINDOW_SECONDS
import com.moneymanager.builtin.StatementDefaults.STATEMENT_PLACEHOLDER_WINDOW
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.ofx.OfxColumns
import com.moneymanager.domain.model.rules.AccountNaming
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.Extraction
import com.moneymanager.domain.model.rules.FeeRule
import com.moneymanager.domain.model.rules.ForeignAmount
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.SettlementWindow
import com.moneymanager.domain.model.rules.ValueExpr
import kotlin.time.Instant

/** Built-in Barclaycard OFX and CSV strategies. */
object BarclaycardStrategies {
    private const val BARCLAYCARD_ACCOUNT = "Barclaycard"

    /**
     * A Barclaycard transaction's FITID: its posting date-time and a per-day counter (`20190722000000001`), which tells
     * a Barclaycard export from another institution's OFX file.
     */
    private const val BARCLAYCARD_FITID = "^\\d{17}$"

    /** A payment into the card (`Payment By Direct Debit`), which never says who paid. */
    private const val BARCLAYCARD_PAYMENT = "(?i)^payment\\b"

    /**
     * A foreign spend's memo: the card, then the amount and currency it was made in, the currency as words and followed
     * by the country (`1234/8.64 US DOLLAR UNITED STATES`).
     */
    private const val BARCLAYCARD_FOREIGN_MEMO = "^\\d+/([\\d.,]+) (\\S.*)$"

    /** The sterling fee a foreign spend's memo ends with (`Transaction Fee £0.62`), included in the row's amount. */
    private const val BARCLAYCARD_TRANSACTION_FEE = "Transaction Fee £([\\d.,]+)"

    /**
     * Built-in OFX strategy for Barclaycard's transaction downloads: one credit-card statement per file, in the
     * currency its `CURDEF` names, dated by day. Downloads overlap (a month's partial file and its full one), so the
     * FITID identifies a transaction across them. A payment into the card names no payer: a placeholder, which
     * reconciles against the paying bank's own record of it, as American Express's does. Barclaycard writes a foreign
     * spend's original amount and currency in the memo, the currency as words and followed by the country
     * (`1234/8.10 EURO FRANCE`), not as OFX's `ORIGCURRENCY`: the currency resolves by its name (see `resolveAssetCode`).
     * The non-sterling transaction fee follows it (`Transaction Fee £0.62`), already part of the sterling amount.
     */
    fun buildBarclaycardOfxStrategy(now: Instant): CsvImportStrategy {
        val fieldMappings =
            mapOf(
                accountFromRules(TransferField.SOURCE_ACCOUNT),
                accountFromRules(TransferField.TARGET_ACCOUNT),
                TransferField.TIMESTAMP to
                    DateTimeParsingMapping(
                        fieldType = TransferField.TIMESTAMP,
                        dateColumnName = OfxColumns.COL_DATE,
                        dateFormat = "yyyy-MM-dd",
                    ),
                TransferField.DESCRIPTION to
                    DirectColumnMapping(
                        fieldType = TransferField.DESCRIPTION,
                        value = ValueExpr.of(OfxColumns.COL_NAME),
                    ),
                TransferField.AMOUNT to
                    AmountParsingMapping(
                        fieldType = TransferField.AMOUNT,
                        mode = AmountMode.SINGLE_COLUMN,
                        amountColumnName = OfxColumns.COL_AMOUNT,
                    ),
                TransferField.CURRENCY to
                    CurrencyLookupMapping(fieldType = TransferField.CURRENCY, value = ValueExpr.of(OfxColumns.COL_CURRENCY)),
                TransferField.TIMEZONE to HardCodedTimezoneMapping(fieldType = TransferField.TIMEZONE, timezoneId = "UTC"),
            )
        val rules =
            listOf(
                attribute(OfxColumns.COL_FITID, "barclaycard-fitid", unique = true),
                rule(
                    RuleAction.Foreign(
                        ForeignAmount(
                            amount = ValueExpr(listOf(OfxColumns.COL_MEMO), Extraction(BARCLAYCARD_FOREIGN_MEMO, "$1")),
                            currency = ValueExpr(listOf(OfxColumns.COL_MEMO), Extraction(BARCLAYCARD_FOREIGN_MEMO, "$2")),
                        ),
                    ),
                    conditions = listOf(Condition(OfxColumns.COL_MEMO, ConditionOp.MATCHES, BARCLAYCARD_FOREIGN_MEMO)),
                    name = "Foreign spend",
                ),
                rule(
                    RuleAction.Fee(
                        FeeRule(
                            amount = ValueExpr(listOf(OfxColumns.COL_MEMO), Extraction(BARCLAYCARD_TRANSACTION_FEE, "$1")),
                            includedInAmount = true,
                        ),
                    ),
                    conditions = listOf(Condition(OfxColumns.COL_MEMO, ConditionOp.MATCHES, BARCLAYCARD_TRANSACTION_FEE)),
                    name = "Transaction fee",
                ),
                // Negative = a purchase on the card; positive = a refund or a payment into it.
                incomingWhenPositive(OfxColumns.COL_AMOUNT),
                accounts(AccountSide.SOURCE, fixedNaming(OfxColumns.COL_DATE, BARCLAYCARD_ACCOUNT)),
                accounts(
                    AccountSide.TARGET,
                    naming(OfxColumns.COL_NAME, pattern = BARCLAYCARD_PAYMENT, unidentified = true),
                    AccountNaming(value = ValueExpr.of(OfxColumns.COL_NAME)),
                ),
            )
        return CsvImportStrategy(
            id = CsvImportStrategyId(barclaycardOfxStrategyId),
            name = "Barclaycard (OFX)",
            config =
                CsvStrategyConfig(
                    identificationColumns = OfxColumns.headers.toSet(),
                    fieldMappings = fieldMappings,
                    rules = rules,
                    contentMatchRules = listOf(Condition(OfxColumns.COL_FITID, ConditionOp.MATCHES, BARCLAYCARD_FITID)),
                    crossSourceReconcileWindow = SettlementWindow.Elapsed(QIF_STATEMENT_RECONCILE_WINDOW_SECONDS),
                    placeholderReconcileWindow = STATEMENT_PLACEHOLDER_WINDOW,
                ),
            createdAt = now,
            updatedAt = now,
        )
    }

    /**
     * Barclaycard's sterling CSV lists purchases as positive and payments to the card as negative.
     * It has no FITID or running balance. Identical purchases on the same day are separate rows, so
     * use the existing one-to-one all-fields dedupe for overlapping downloads.
     */
    fun buildBarclaycardCsvStrategy(
        now: Instant,
        currencyId: CurrencyId,
    ): CsvImportStrategy {
        val date = "Date"
        val card = "Account/Card No"
        val amount = "Amount"
        val subcategory = "Subcategory"
        val memo = "Memo"
        // A tab or padding separates the merchant label from an additional description.
        val merchant = ValueExpr.of(memo, extraction = Extraction("^\\s*(\\S.*?)(?:\\t| {2,}|$)", "$1"))
        return CsvImportStrategy(
            id = CsvImportStrategyId(barclaycardCsvStrategyId),
            name = "Barclaycard CSV",
            config =
                CsvStrategyConfig(
                    identificationColumns = setOf(date, card, amount, subcategory, memo),
                    fieldMappings =
                        mapOf(
                            accountFromRules(TransferField.SOURCE_ACCOUNT),
                            accountFromRules(TransferField.TARGET_ACCOUNT),
                            TransferField.TIMESTAMP to
                                DateTimeParsingMapping(
                                    fieldType = TransferField.TIMESTAMP,
                                    dateColumnName = date,
                                    dateFormat = "dd/MM/yyyy",
                                ),
                            TransferField.DESCRIPTION to DirectColumnMapping(fieldType = TransferField.DESCRIPTION, value = merchant),
                            TransferField.AMOUNT to
                                AmountParsingMapping(
                                    fieldType = TransferField.AMOUNT,
                                    mode = AmountMode.SINGLE_COLUMN,
                                    amountColumnName = amount,
                                ),
                            TransferField.CURRENCY to HardCodedCurrencyMapping(fieldType = TransferField.CURRENCY, currencyId = currencyId),
                            TransferField.TIMEZONE to HardCodedTimezoneMapping(fieldType = TransferField.TIMEZONE, timezoneId = "UTC"),
                        ),
                    rules =
                        listOf(
                            incomingWhen(Condition(amount, ConditionOp.NEGATIVE)),
                            attribute(card, "barclaycard-card-number"),
                            attribute(subcategory, "barclaycard-subcategory"),
                            attribute(memo, "barclaycard-memo"),
                            accounts(AccountSide.SOURCE, fixedNaming(card, BARCLAYCARD_ACCOUNT)),
                            rule(
                                RuleAction.Account(AccountSide.TARGET, AccountNaming(value = merchant, unidentified = true)),
                                conditions = listOf(Condition(subcategory, ConditionOp.EQUALS, "Payment received")),
                            ),
                            accounts(AccountSide.TARGET, AccountNaming(value = merchant)),
                        ),
                    duplicateDateTolerance = SettlementWindow.Elapsed(0),
                    crossSourceReconcileWindow = SettlementWindow.Elapsed(QIF_STATEMENT_RECONCILE_WINDOW_SECONDS),
                    placeholderReconcileWindow = STATEMENT_PLACEHOLDER_WINDOW,
                ),
            createdAt = now,
            updatedAt = now,
        )
    }
}

package com.moneymanager.builtin

import com.moneymanager.builtin.BuiltInCsvStrategies.amexPdfStrategyId
import com.moneymanager.builtin.BuiltInCsvStrategies.amexQifStrategyId
import com.moneymanager.builtin.StatementDefaults.QIF_STATEMENT_RECONCILE_WINDOW_SECONDS
import com.moneymanager.builtin.StatementDefaults.STATEMENT_PLACEHOLDER_WINDOW
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.PdfLineConfig
import com.moneymanager.domain.model.csvstrategy.ReferenceDate
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.qif.QifColumns
import com.moneymanager.domain.model.rules.AccountNaming
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.CaptureTarget
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.Extraction
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleValue
import com.moneymanager.domain.model.rules.SettlementWindow
import com.moneymanager.domain.model.rules.ValueExpr
import kotlin.time.Instant

/** Built-in American Express QIF and PDF strategies. */
object AmexStrategies {
    /**
     * An American Express payee recording a payment INTO the card ("PAYMENT RECEIVED - THANK YOU"). It never
     * says who paid, so the payer is a placeholder that reconciles against the paying bank's own record.
     */
    private const val AMEX_ACCOUNT = "American Express"
    private const val AMEX_PAYMENT_RECEIVED = "^(?:PAYMENT|DIRECT DEBIT) RECEIVED\\b"

    /** The column the American Express PDF strategy assembles a row's date in ("2 Jan"). */
    private const val AMEX_PDF_DATE = "date"

    /** The memo every American Express QIF record ends with; group 1 is the date. */
    private const val AMEX_PROCESS_DATE = "\\bProcess Date (\\d{2}/\\d{2}/\\d{4})\\s*$"

    /**
     * Built-in QIF strategy for American Express UK card statements. Every Amex record carries a
     * "Process Date dd/mm/yyyy" memo (after an optional merchant type such as "RETAIL GOODS"), which
     * content-matches these files (QIF's fixed columns can't tell banks apart). The payee is
     * the merchant (the target account); the check-number field holds Amex's own reference, the same in
     * every statement that covers the transaction, so overlapping exports dedupe on it. The source is the
     * card's own account, "American Express", wherever the file was found.
     */
    fun buildAmexQifStrategy(
        now: Instant,
        currencyId: CurrencyId,
    ): CsvImportStrategy {
        val fieldMappings =
            mapOf(
                accountFromRules(TransferField.SOURCE_ACCOUNT),
                accountFromRules(TransferField.TARGET_ACCOUNT),
                TransferField.TIMESTAMP to
                    DateTimeParsingMapping(
                        fieldType = TransferField.TIMESTAMP,
                        dateColumnName = QifColumns.COL_DATE,
                        dateFormat = "dd/MM/yyyy",
                    ),
                TransferField.DESCRIPTION to
                    DirectColumnMapping(
                        fieldType = TransferField.DESCRIPTION,
                        value = ValueExpr.of(QifColumns.COL_PAYEE),
                    ),
                TransferField.AMOUNT to
                    AmountParsingMapping(
                        fieldType = TransferField.AMOUNT,
                        mode = AmountMode.SINGLE_COLUMN,
                        amountColumnName = QifColumns.COL_AMOUNT,
                    ),
                TransferField.CURRENCY to HardCodedCurrencyMapping(fieldType = TransferField.CURRENCY, currencyId = currencyId),
                TransferField.TIMEZONE to HardCodedTimezoneMapping(fieldType = TransferField.TIMEZONE, timezoneId = "UTC"),
            )
        val rules =
            listOf(
                attribute(QifColumns.COL_CHECK_NUMBER, "amex-reference", unique = true),
                attribute(
                    QifColumns.COL_MEMO,
                    "amex-process-date",
                    extraction = Extraction(pattern = AMEX_PROCESS_DATE, outputTemplate = "$1"),
                ),
                attribute(
                    QifColumns.COL_MEMO,
                    "amex-merchant-type",
                    extraction = Extraction(pattern = "^\\s*(\\S.*?)\\s+Process Date\\b", outputTemplate = "$1"),
                ),
                // Negative = a purchase on the card; positive = a refund, payment or cashback.
                incomingWhenPositive(QifColumns.COL_AMOUNT),
                accounts(AccountSide.SOURCE, fixedNaming(QifColumns.COL_DATE, AMEX_ACCOUNT)),
                accounts(
                    AccountSide.TARGET,
                    // A payment to the card names no payer: a placeholder, which reconciles against the
                    // paying bank's own record of the payment (its direct debit to the card) in either import
                    // order, so the payment is counted once.
                    naming(QifColumns.COL_PAYEE, pattern = AMEX_PAYMENT_RECEIVED, unidentified = true),
                    AccountNaming(value = ValueExpr.of(QifColumns.COL_PAYEE)),
                ),
            )
        return CsvImportStrategy(
            id = CsvImportStrategyId(amexQifStrategyId),
            name = "American Express (QIF)",
            config =
                CsvStrategyConfig(
                    identificationColumns = QifColumns.headers.toSet(),
                    fieldMappings = fieldMappings,
                    rules = rules,
                    contentMatchRules = listOf(Condition(QifColumns.COL_MEMO, ConditionOp.MATCHES, AMEX_PROCESS_DATE)),
                    crossSourceReconcileWindow = SettlementWindow.Elapsed(QIF_STATEMENT_RECONCILE_WINDOW_SECONDS),
                    placeholderReconcileWindow = STATEMENT_PLACEHOLDER_WINDOW,
                ),
            createdAt = now,
            updatedAt = now,
        )
    }

    /**
     * Built-in PDF strategy for American Express UK monthly statements, every layout since 2008. Its line rules
     * read the statement date from the page header (`xxxx-xxxxxx-01234   19/01/13`), start a row at each
     * transaction line (one date in the 2008 layout, `February 28  MERCHANT  29.29`; transaction and process dates
     * since, `Jan 2  Jan 3  MERCHANT  ref  53.98  42.87`, the last amount sterling) and add what the lines below
     * it say: `CR` (alone, or after the currency's name) marks a credit, and a foreign spend's exchange rate and
     * fee. A section's `Total …` line ends the row before it, as a statement's total can itself be a credit.
     *
     * Rows carry no year, so the date is read against the statement's. A statement prints no reference either,
     * and the same purchase can appear twice on one, so a row is identified by its statement and position.
     * Payments into the card name no payer: a placeholder, reconciling against the paying bank's record of the
     * payment as in [buildAmexQifStrategy], whose records of the same purchases these reconcile with too.
     */
    fun buildAmexPdfStrategy(
        now: Instant,
        currencyId: CurrencyId,
    ): CsvImportStrategy {
        val month = "(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*"
        val money = "[\\d,]+\\.\\d\\d"
        val lineRules =
            listOf(
                rule(
                    RuleAction.Capture(
                        PdfLineConfig.TEXT,
                        "xxxx-xxxxxx-\\d+\\s+(?<statementDate>\\d{2}/\\d{2}/\\d{2})\\b",
                        CaptureTarget.DOCUMENT,
                    ),
                    name = "Statement date",
                ),
                rule(
                    RuleAction.Capture(
                        PdfLineConfig.TEXT,
                        "^(?<month>Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]* +(?<day>\\d{1,2})" +
                            "(?: +$month +\\d{1,2} +| {2,})(?<description>.+?) {2,}" +
                            "(?:(?<foreignAmount>$money) +)?(?<amount>$money)$",
                        CaptureTarget.NEW_ROW,
                    ),
                    RuleAction.SetValue(AMEX_PDF_DATE, RuleValue.Template("{day} {month}")),
                    name = "Transaction",
                ),
                rule(
                    RuleAction.Capture(PdfLineConfig.TEXT, "^(?:.*\\s)?(?<credit>CR)$", CaptureTarget.ROW),
                    name = "Credit",
                ),
                rule(
                    RuleAction.Capture(PdfLineConfig.TEXT, "\\bExchange Rate (?<exchangeRate>\\d*\\.\\d+)", CaptureTarget.ROW),
                    name = "Exchange rate",
                ),
                rule(
                    RuleAction.Capture(PdfLineConfig.TEXT, "\\bNonsterling Transaction Fee (?<foreignFee>\\d*\\.\\d+)", CaptureTarget.ROW),
                    name = "Foreign fee",
                ),
                rule(RuleAction.Capture(PdfLineConfig.TEXT, "^Total\\b", CaptureTarget.NEW_ROW), name = "Section total"),
            )
        val fieldMappings =
            mapOf(
                accountFromRules(TransferField.TARGET_ACCOUNT),
                TransferField.TIMESTAMP to
                    DateTimeParsingMapping(
                        fieldType = TransferField.TIMESTAMP,
                        dateColumnName = AMEX_PDF_DATE,
                        dateFormat = "d MMM",
                        yearFrom = ReferenceDate("statementDate", "dd/MM/yy"),
                    ),
                TransferField.DESCRIPTION to
                    DirectColumnMapping(
                        fieldType = TransferField.DESCRIPTION,
                        value = ValueExpr.of("description"),
                    ),
                TransferField.AMOUNT to
                    AmountParsingMapping(fieldType = TransferField.AMOUNT, mode = AmountMode.SINGLE_COLUMN, amountColumnName = "amount"),
                TransferField.CURRENCY to HardCodedCurrencyMapping(fieldType = TransferField.CURRENCY, currencyId = currencyId),
                TransferField.TIMEZONE to HardCodedTimezoneMapping(fieldType = TransferField.TIMEZONE, timezoneId = "UTC"),
                accountFromRules(TransferField.SOURCE_ACCOUNT),
            )
        val rules =
            listOf(
                // The rows a section's total line started.
                rule(RuleAction.Skip, conditions = listOf(Condition(AMEX_PDF_DATE, ConditionOp.BLANK))),
                rule(
                    RuleAction.Attribute(
                        "amex-statement-line",
                        RuleValue.Template("{statementDate} {page}:{line}"),
                        uniqueIdentifier = true,
                    ),
                ),
                attribute("foreignAmount", "amex-foreign-amount"),
                attribute("exchangeRate", "amex-exchange-rate"),
                attribute("foreignFee", "amex-foreign-fee"),
                incomingWhen(Condition("credit", ConditionOp.NOT_BLANK)),
                // The statement is the card's own, wherever the file was found: the folder's account doesn't apply.
                accounts(AccountSide.SOURCE, fixedNaming("description", AMEX_ACCOUNT)),
                accounts(
                    AccountSide.TARGET,
                    // Payments in, and a credit balance paid back out, name no bank: placeholders that reconcile
                    // against the bank's own record of the movement.
                    naming("description", pattern = AMEX_PAYMENT_RECEIVED, unidentified = true),
                    naming("description", pattern = "^CREDIT BALANCE REFUNDED\\b", unidentified = true),
                    // The statement's column gaps collapse to single spaces, as the merchant reads in Amex's other exports.
                    naming("description", pattern = "(?<merchant>.+)", name = $$"${merchant}"),
                ),
            )
        return CsvImportStrategy(
            id = CsvImportStrategyId(amexPdfStrategyId),
            name = "American Express (PDF)",
            config =
                CsvStrategyConfig(
                    identificationColumns = PdfLineConfig.BASE_COLUMNS.toSet(),
                    fieldMappings = fieldMappings,
                    rules = rules,
                    contentMatchRules = listOf(Condition(PdfLineConfig.TEXT, ConditionOp.MATCHES, "\\bamericanexpress\\.co\\.uk\\b")),
                    crossSourceReconcileWindow = SettlementWindow.Elapsed(QIF_STATEMENT_RECONCILE_WINDOW_SECONDS),
                    placeholderReconcileWindow = STATEMENT_PLACEHOLDER_WINDOW,
                    pdfLines = PdfLineConfig(lineRules),
                ),
            createdAt = now,
            updatedAt = now,
        )
    }
}

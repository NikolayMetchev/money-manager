package com.moneymanager.builtin

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
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.qif.QifColumns
import com.moneymanager.domain.model.rules.AccountNaming
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.Extraction
import com.moneymanager.domain.model.rules.SettlementWindow
import com.moneymanager.domain.model.rules.ValueExpr
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Built-in Santander QIF strategy. */
object SantanderQifStrategies {
    private val santanderQifStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000006")

    /**
     * Built-in QIF strategy for Santander UK statement exports. Santander packs the whole
     * transaction description into the QIF Payee field (e.g. "DIRECT DEBIT PAYMENT TO AMERICAN
     * EXPRESS REF …, MANDATE NO 0013, 1352.23"). This strategy parses it entirely via config: regex
     * rules extract the clean counterparty into the target account (flagging person-to-person payees
     * so the import also creates a Person + ownership), attribute mappings pull out the transaction
     * type / reference / mandate / posted date, and the description is cleaned of its trailing amount.
     *
     * Content-match conditions let the QIF apply flow auto-detect this strategy from the Payee content.
     * The source account and currency are chosen at import time (QIF data carries neither).
     */
    @Suppress("LongMethod")
    fun buildSantanderQifStrategy(
        now: Instant,
        currencyId: CurrencyId,
    ): CsvImportStrategy {
        // First-match-wins rules over the Payee field; ${cp} extracts the clean counterparty name.
        // Tuned against a full Santander statement history (~7k records, ~99.9% parsed). The middle
        // rules use ".*?\bTO/\bFROM" to skip "VIA FASTER PAYMENT" / "REF.<x>" preludes that vary per row.
        // A pended payment to someone is theirs; any other PENDED narrative is an account fee.
        val pendedPayment = "PENDED\\b.*?\\bTO\\s+(.+?)(?:\\s+REFERENCE\\b|,)"
        val counterparties =
            arrayOf(
                // Cashback summary lines ("1 Direct Debit Payment for Water at 1,00% Cashback, 0.36").
                naming(
                    QifColumns.COL_PAYEE,
                    pattern = "^\\d+ Direct Debit Payments?\\b",
                    name = "Santander Cashback",
                ),
                naming(QifColumns.COL_PAYEE, pattern = "^MY OFFERS\\b", name = "Santander Cashback"),
                naming(
                    QifColumns.COL_PAYEE,
                    pattern = "^DIRECT DEBIT INDEMNITY\\b",
                    name = "Santander Direct Debit Indemnity",
                ),
                naming(
                    QifColumns.COL_PAYEE,
                    pattern = "^DIRECT DEBIT REVERSAL\\b",
                    name = "Santander Direct Debit Reversal",
                ),
                naming(
                    QifColumns.COL_PAYEE,
                    pattern = "^DIRECT DEBIT PAYMENT TO\\s+(.+?)(?:\\s+REF\\b|\\s+MANDATE\\b|,)",
                    name = "$1",
                    fallbackName = "Santander Direct Debit",
                ),
                naming(
                    QifColumns.COL_PAYEE,
                    pattern = "^CARD PAYMENT TO\\s+(.+?)(?:,|\\s+ON\\b)",
                    name = "$1",
                    fallbackName = "Santander Card Payment",
                ),
                naming(
                    QifColumns.COL_PAYEE,
                    pattern = "^FASTER PAYMENTS RECEIPT\\b.*?\\bFROM\\s+([^,]+?)\\s*,",
                    name = "$1",
                    fallbackName = "Santander Faster Payment",
                    person = true,
                ),
                naming(
                    QifColumns.COL_PAYEE,
                    pattern = "^PAYM\\b.*?\\bFROM\\s+([^,]+?)\\s*(?:,|\\s+REFERENCE\\b)",
                    name = "$1",
                    fallbackName = "Santander Paym",
                    person = true,
                ),
                naming(
                    QifColumns.COL_PAYEE,
                    pattern = "^PAYM\\b.*?\\bTO\\s+(.+?)(?:\\s+REFERENCE\\b|,)",
                    name = "$1",
                    fallbackName = "Santander Paym",
                    person = true,
                ),
                naming(
                    QifColumns.COL_PAYEE,
                    pattern = "^BILL PAYMENT\\b.*?\\bTO\\s+(.+?)(?:\\s+REFERENCE\\b|\\s+MANDATE\\b|,)",
                    name = "$1",
                    fallbackName = "Santander Bill Payment",
                    person = true,
                ),
                naming(
                    QifColumns.COL_PAYEE,
                    pattern = "^BILL PAYMENT\\b.*?\\bFROM\\s+([^,]+?)\\s*(?:,|\\s+REFERENCE\\b)",
                    name = "$1",
                    fallbackName = "Santander Bill Payment",
                    person = true,
                ),
                naming(
                    QifColumns.COL_PAYEE,
                    pattern = "^$pendedPayment",
                    name = "$1",
                    fallbackName = "Santander Pended Payment",
                    person = true,
                ),
                naming(
                    QifColumns.COL_PAYEE,
                    pattern = "^STANDING ORDER\\b.*?\\bTO\\s+(.+?)(?:\\s+REFERENCE\\b|\\s+MANDATE\\b|,)",
                    name = "$1",
                    fallbackName = "Santander Standing Order",
                    person = true,
                ),
                naming(
                    QifColumns.COL_PAYEE,
                    pattern = "^Third party payment\\b.*?\\bto\\s+(.+?)(?:\\s+Reference\\b|,)",
                    name = "$1",
                    fallbackName = "Santander Third Party Payment",
                    person = true,
                ),
                naming(
                    QifColumns.COL_PAYEE,
                    pattern = "^TRANSFER\\b.*?\\bTO\\s+(.+?)(?:\\s+REFERENCE\\b|,)",
                    name = "$1",
                    fallbackName = "Santander Transfer",
                    person = true,
                ),
                naming(
                    QifColumns.COL_PAYEE,
                    pattern = "^TRANSFER\\b.*?\\bFROM\\s+([^,.]+?)\\s*(?:,|\\.|\\s+REFERENCE\\b)",
                    name = "$1",
                    fallbackName = "Santander Transfer",
                    person = true,
                ),
                // Cash / cheque movements consolidate to a single account each.
                naming(QifColumns.COL_PAYEE, pattern = "^CASH\\b", name = "Cash"),
                naming(QifColumns.COL_PAYEE, pattern = "^WITHDRAWAL\\b", name = "Cash"),
                naming(QifColumns.COL_PAYEE, pattern = "^CHEQUE\\b", name = "Cheque"),
                // Bank giro credit refs are often "<digits><NAME>"; strip leading digits to consolidate.
                naming(
                    QifColumns.COL_PAYEE,
                    pattern = "^BANK GIRO CREDIT REF\\s+\\d*([^,]+?)\\s*,",
                    name = "$1",
                    fallbackName = "Santander Bank Giro Credit",
                ),
                naming(
                    QifColumns.COL_PAYEE,
                    pattern = "^CREDIT FROM\\s+(.+?)(?:\\s+ON\\b|,)",
                    name = "$1",
                    fallbackName = "Santander Credit",
                ),
                naming(QifColumns.COL_PAYEE, pattern = "^INTEREST\\b", name = "Santander Interest"),
                naming(QifColumns.COL_PAYEE, pattern = "^LOAN\\b", name = "Santander Loan"),
            )
        val fieldMappings =
            mapOf(
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
                        value =
                            ValueExpr(
                                listOf(QifColumns.COL_PAYEE, QifColumns.COL_MEMO),
                                extraction = Extraction(pattern = "^(.*?),\\s*[\\d][\\d.,]*\\s*(?:GBP)?\\s*$", outputTemplate = "$1"),
                            ),
                    ),
                TransferField.AMOUNT to
                    AmountParsingMapping(
                        fieldType = TransferField.AMOUNT,
                        mode = AmountMode.SINGLE_COLUMN,
                        amountColumnName = QifColumns.COL_AMOUNT,
                    ),
                TransferField.CURRENCY to
                    HardCodedCurrencyMapping(
                        fieldType = TransferField.CURRENCY,
                        // The QIF apply flow pre-selects this as the default (GBP for Santander); the
                        // user can override it.
                        currencyId = currencyId,
                    ),
                TransferField.TIMEZONE to
                    HardCodedTimezoneMapping(
                        fieldType = TransferField.TIMEZONE,
                        timezoneId = "UTC",
                    ),
            )
        // Transaction type: mutually-exclusive anchored patterns tag a fixed label on the matching row.
        val typeMappings =
            listOf(
                "^\\d+ Direct Debit" to "CASHBACK",
                "^MY OFFERS" to "CASHBACK",
                "^CARD PAYMENT TO" to "CARD_PAYMENT",
                "^DIRECT DEBIT" to "DIRECT_DEBIT",
                "^FASTER PAYMENTS RECEIPT" to "FASTER_PAYMENT_IN",
                "^PAYM\\b" to "PAYM",
                "^BILL PAYMENT" to "BILL_PAYMENT",
                "^STANDING ORDER" to "STANDING_ORDER",
                "^Third party payment" to "THIRD_PARTY_PAYMENT",
                "^TRANSFER\\b" to "TRANSFER",
                "^CASH\\b" to "CASH",
                "^CHEQUE\\b" to "CHEQUE",
                "^BANK GIRO CREDIT" to "BANK_GIRO_CREDIT",
                "^CREDIT FROM" to "CREDIT",
                "^INTEREST" to "INTEREST",
                "^LOAN\\b" to "LOAN",
                "^(MONTHLY ACCOUNT FEE|MAINTAINING THE ACCOUNT)" to "ACCOUNT_FEE",
            ).map { (pattern, label) ->
                attribute(QifColumns.COL_PAYEE, "santander-transaction-type", extraction = Extraction(pattern = pattern), label = label)
            }
        val rules =
            typeMappings +
                listOf(
                    attribute(
                        QifColumns.COL_PAYEE,
                        "santander-reference",
                        extraction =
                            Extraction(
                                pattern = "(?:REFERENCE|REF)\\b\\.?\\s*(.+?)\\s*(?:,|\\s+MANDATE\\b)",
                                outputTemplate = "$1",
                            ),
                    ),
                    attribute(
                        QifColumns.COL_PAYEE,
                        "santander-mandate",
                        extraction = Extraction(pattern = "MANDATE NO\\s*(\\d+)", outputTemplate = "$1"),
                    ),
                    attribute(
                        QifColumns.COL_PAYEE,
                        "santander-posted-date",
                        extraction = Extraction(pattern = "\\bON\\s+(\\d{2}-\\d{2}-\\d{4})", outputTemplate = "$1"),
                    ),
                    incomingWhenPositive(QifColumns.COL_AMOUNT),
                    // The account-fee narratives are the account's own fees, booked to its fee account.
                    wholeFeeWhen(
                        QifColumns.COL_AMOUNT,
                        QifColumns.COL_PAYEE,
                        "^(?!$pendedPayment)(MONTHLY ACCOUNT FEE|MAINTAINING THE ACCOUNT|UNARRANGED|NON-STERLING|PENDED|PENDING)\\b",
                    ),
                    accounts(
                        AccountSide.TARGET,
                        *counterparties,
                        AccountNaming(value = ValueExpr.of(QifColumns.COL_PAYEE, QifColumns.COL_TRANSFER_ACCOUNT, QifColumns.COL_CATEGORY)),
                    ),
                )
        return CsvImportStrategy(
            id = CsvImportStrategyId(santanderQifStrategyId),
            name = "Santander (QIF)",
            config =
                CsvStrategyConfig(
                    identificationColumns = QifColumns.headers.toSet(),
                    fieldMappings = fieldMappings,
                    rules = rules,
                    contentMatchRules =
                        listOf(
                            Condition(
                                QifColumns.COL_PAYEE,
                                ConditionOp.MATCHES,
                                "^(CARD PAYMENT|DIRECT DEBIT|\\d+ DIRECT DEBIT|FASTER PAYMENTS RECEIPT|PAYM|BILL PAYMENT|" +
                                    "STANDING ORDER|Third party payment|CASH |CHEQUE|BANK GIRO CREDIT|MONTHLY ACCOUNT FEE|" +
                                    "INTEREST|MAINTAINING THE ACCOUNT|CREDIT FROM|TRANSFER)",
                            ),
                        ),
                    // Opts in to cross-source reconciliation, so a payment the bank names both ends of (a direct
                    // debit to a card) supersedes the card statement's placeholder record of it.
                    crossSourceReconcileWindow = SettlementWindow.Elapsed(QIF_STATEMENT_RECONCILE_WINDOW_SECONDS),
                    placeholderReconcileWindow = STATEMENT_PLACEHOLDER_WINDOW,
                ),
            createdAt = now,
            updatedAt = now,
        )
    }
}

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
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.Extraction
import com.moneymanager.domain.model.rules.ForeignAmount
import com.moneymanager.domain.model.rules.LegRole
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.SettlementWindow
import com.moneymanager.domain.model.rules.ValueExpr
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Built-in crypto.com card, fiat and crypto CSV strategies. */
object CryptoComStrategies {
    private val cryptoComCardXlsxStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000b")

    private val cryptoComCryptoStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000009")

    private val cryptoComFiatStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000008")

    private val cryptoComCardStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000007")

    /** Fixed account names shared by the crypto.com Card and Fiat strategies, so both files resolve the same accounts. */
    private const val CRYPTO_COM_CARD_ACCOUNT = "Crypto.com Card"
    private const val CRYPTO_COM_CASH_ACCOUNT = "Crypto.com Cash"

    /** Single account that holds every crypto.com crypto balance (one per-asset balance, not per account). */
    private const val CRYPTO_COM_CRYPTO_ACCOUNT = "Crypto.com"

    /**
     * Trims the trailing city/country padding from the xlsx card export's fixed-width
     * `Card Acceptor Name` field (e.g. `"Spotify UK               Stockholm      SWE"` ->
     * `"Spotify UK"`, `"CRV*Card verification    London         GBR"` -> `"CRV*Card verification"`),
     * while leaving any leading marker (like "CRV*") untouched for [BuiltInPassThroughs] to detect.
     * Anchors on the first non-space character (`\s*(\S.*?)`) rather than a bare lazy `(.+?)`: for a
     * value that's pure padding + a trailing country code (e.g. "                    GBR", no real
     * merchant name), a bare `(.+?)` backtracks to a single leading space instead of skipping to "GBR",
     * producing a blank/whitespace-only description.
     */
    private const val CARD_ACCEPTOR_NAME_TRIM_PATTERN = "^\\s*(\\S.*?)(?:\\s{2,}.*)?$"

    /**
     * The Crypto.com Exchange account (also created by the Exchange API strategy). Routing the App's
     * "App wallet -> Exchange" / "Exchange -> App wallet" transfers to it — instead of a description-named
     * placeholder — makes the CSV and the API emit the identical `Crypto.com -> Crypto.com Exchange`
     * transfer, so cross-source reconciliation links the two ends regardless of which imports first.
     */
    private const val CRYPTO_COM_EXCHANGE_ACCOUNT = "Crypto.com Exchange"

    /**
     * Counterparty account for crypto.com asset conversions that arrive as separate debited/credited
     * rows (dust conversions, wallet swaps). The dusted assets flow Crypto.com -> here and the received
     * asset flows here -> Crypto.com, so the Crypto.com balances stay exact and the (economically
     * meaningless) mixed-asset residual is isolated in this one account. See [LegAssembly.ThroughAccount].
     */
    private const val CRYPTO_COM_CONVERSIONS_ACCOUNT = "Crypto.com Conversions"

    /**
     * Placeholder counterparty for a card top-up seen only from the card's side. The card statement
     * records that money arrived ("GBP Deposit", "Card Load") but never where from — it may be the fiat
     * Cash wallet or, as in crypto.com's pre-mid-2022 TGBP era, a crypto wallet. Booking it against Cash
     * would debit an account that never paid; the row lands here instead and reconciles away against the
     * funding side's own record of the movement when one is imported (see
     * `AccountNaming.unidentified`).
     */
    private const val CRYPTO_COM_CARD_TOP_UP_ACCOUNT = "Crypto.com Card Top Up"

    /**
     * Cross-source reconciliation window for the crypto.com strategies. The same top-up appears in
     * both the card export ("GBP Deposit") and the fiat export (viban_card_top_up) with near-identical
     * timestamps, but statement exports are less precise than API feeds, so allow up to an hour.
     */
    private const val CRYPTO_COM_RECONCILE_WINDOW_SECONDS = 3600L

    /**
     * Window for pairing a conversion's debit leg to its credit leg. Crypto.com stamps the two legs of
     * one event within ~1s of each other (occasionally straddling a second boundary), while distinct
     * conversion events are minutes-to-days apart, so a few seconds cleanly separates events.
     */
    private const val CRYPTO_COM_CONVERSION_PAIRING_WINDOW_SECONDS = 5L

    /** The Crypto.com Crypto strategy's leg group pairing dust conversions and wallet swaps. */
    private const val CRYPTO_COM_CONVERSION_GROUP = "conversion"

    /**
     * The column header shared by all three crypto.com exports (card_transactions_record_*,
     * fiat_transactions_record_* and crypto_transactions_record_*). Because the sets are identical,
     * the crypto.com strategies rely on [CsvStrategyConfig.fileNamePattern] and content rules over
     * the Transaction Kind column to tell the three files apart.
     */
    private val cryptoComIdentificationColumns =
        setOf(
            "Timestamp (UTC)",
            "Transaction Description",
            "Currency",
            "Amount",
            "To Currency",
            "To Amount",
            "Native Currency",
            "Native Amount",
            "Native Amount (in USD)",
            "Transaction Kind",
            "Transaction Hash",
        )

    /**
     * Built-in strategy for crypto.com's card_transactions_record_*.csv export (the Visa card
     * statement). Every row has a blank Transaction Kind — the content rule that identifies the file
     * when it has been renamed. Spends are negative Native Amounts to a merchant account looked up
     * from the description; "GBP Deposit" rows (described as "GBP -> GBP" in pre-mid-2022 exports)
     * are card top-ups arriving from the crypto.com Cash account (the fiat export records the same
     * movement as viban_card_top_up, which cross-source reconciliation links instead of
     * double-counting).
     */
    fun buildCryptoComCardStrategy(now: Instant): CsvImportStrategy {
        val fieldMappings =
            mapOf(
                accountFromRules(TransferField.SOURCE_ACCOUNT),
                accountFromRules(TransferField.TARGET_ACCOUNT),
                TransferField.TIMESTAMP to
                    DateTimeParsingMapping(
                        fieldType = TransferField.TIMESTAMP,
                        dateColumnName = "Timestamp (UTC)",
                        dateFormat = "yyyy-MM-dd",
                        dateTimeFormat = "yyyy-MM-dd HH:mm:ss",
                    ),
                TransferField.DESCRIPTION to
                    DirectColumnMapping(fieldType = TransferField.DESCRIPTION, value = ValueExpr(listOf("Transaction Description"))),
                TransferField.AMOUNT to
                    AmountParsingMapping(
                        fieldType = TransferField.AMOUNT,
                        mode = AmountMode.SINGLE_COLUMN,
                        amountColumnName = "Native Amount",
                    ),
                TransferField.CURRENCY to
                    CurrencyLookupMapping(fieldType = TransferField.CURRENCY, value = ValueExpr(listOf("Native Currency"))),
                TransferField.TIMEZONE to
                    HardCodedTimezoneMapping(
                        fieldType = TransferField.TIMEZONE,
                        timezoneId = "UTC",
                    ),
            )
        val rules =
            listOf(
                attribute("Currency", "cryptocom-txn-currency"),
                attribute("Amount", "cryptocom-txn-amount"),
                attribute("Native Amount (in USD)", "cryptocom-usd-amount"),
                attribute("Transaction Hash", "cryptocom-hash"),
                // Negative = money out of the card; positive (top-ups, refunds) flows in. Native is what the
                // card settled; Currency/Amount is what the merchant was paid, booked as the movement (with the
                // card's conversion as a trade) when the two currencies differ.
                incomingWhenPositive("Native Amount"),
                rule(RuleAction.Foreign(ForeignAmount(amount = ValueExpr.of("Amount"), currency = ValueExpr.of("Currency")))),
                // Fixed-name source so the card and fiat strategies resolve the same account pair, which
                // direction-sensitive reconciliation requires. Users with an existing differently-named card
                // account remap once.
                accounts(AccountSide.SOURCE, fixedNaming("Transaction Description", CRYPTO_COM_CARD_ACCOUNT)),
                accounts(
                    AccountSide.TARGET,
                    // Top-ups ("GBP Deposit", "EUR Deposit", …). The statement does not say which wallet funded
                    // them, so they land on the top-up placeholder and reconcile against the funding side's own
                    // record when it is imported.
                    naming(
                        "Transaction Description",
                        pattern = "^[A-Z]{3,5} Deposit$",
                        name = CRYPTO_COM_CARD_TOP_UP_ACCOUNT,
                        unidentified = true,
                    ),
                    // Pre-mid-2022 exports describe the same top-up as "GBP -> GBP" (same currency on both
                    // sides). Cross-currency arrows (e.g. "TGBP -> GBP") are conversion-funded and deliberately
                    // NOT matched — the crypto export records that movement separately.
                    naming(
                        "Transaction Description",
                        pattern = "^([A-Z]{3,5}) -> \\1$",
                        name = CRYPTO_COM_CARD_TOP_UP_ACCOUNT,
                        unidentified = true,
                    ),
                    naming("Transaction Description"),
                ),
            )
        return CsvImportStrategy(
            id = CsvImportStrategyId(cryptoComCardStrategyId),
            name = "Crypto.com Card",
            config =
                CsvStrategyConfig(
                    identificationColumns = cryptoComIdentificationColumns,
                    fieldMappings = fieldMappings,
                    rules = rules,
                    contentMatchRules = listOf(Condition("Transaction Kind", ConditionOp.MATCHES, "^$")),
                    fileNamePattern = "^card_transactions_record_",
                    crossSourceReconcileWindow = SettlementWindow.Elapsed(CRYPTO_COM_RECONCILE_WINDOW_SECONDS),
                ),
            createdAt = now,
            updatedAt = now,
        )
    }

    /**
     * Built-in strategy for crypto.com's older "Card Transaction History" Excel export (an .xlsx
     * workbook of the same Visa card, before crypto.com switched to the card_transactions_record_*.csv
     * format above — same source account, entirely different columns and header names). Every row's
     * `Amount Processed` is already signed (negative = spend, positive = card load/refund), so no
     * per-row direction logic is needed beyond the standard positive-amount source/target flip.
     *
     * `Amount Processed` is what the card settled, in the card's currency, which the export never names: it
     * is fixed to GBP here (a card issued elsewhere needs it changed). `Currency `/`Amount Requested` are
     * what the merchant asked for, so a multi-currency row ("POS Sig Pur Multi Curr") is booked in that
     * currency with the card's conversion as a trade — the same shape the CSV export above produces, so
     * where the two overlap they still deduplicate.
     */
    fun buildCryptoComCardXlsxStrategy(now: Instant): CsvImportStrategy {
        val fieldMappings =
            mapOf(
                accountFromRules(TransferField.SOURCE_ACCOUNT),
                accountFromRules(TransferField.TARGET_ACCOUNT),
                TransferField.TIMESTAMP to
                    DateTimeParsingMapping(
                        fieldType = TransferField.TIMESTAMP,
                        dateColumnName = "Transaction Date",
                        dateFormat = "MM/dd/yyyy",
                        timeColumnName = "Transaction Time",
                        timeFormat = "HH:mm:ss",
                    ),
                // Card Acceptor Name (not the near-constant Description column) carries the real
                // merchant/CRV* marker; pass-through detection ([PassThroughDetector]) matches against
                // this field, so it must be the source here, trimmed of trailing city/country padding
                // but with any "CRV*" prefix left intact for the detector to peel.
                TransferField.DESCRIPTION to
                    DirectColumnMapping(
                        fieldType = TransferField.DESCRIPTION,
                        value =
                            ValueExpr(
                                listOf("Card Acceptor Name", "Description"),
                                extraction = Extraction(pattern = CARD_ACCEPTOR_NAME_TRIM_PATTERN, outputTemplate = "$1"),
                            ),
                    ),
                TransferField.AMOUNT to
                    AmountParsingMapping(
                        fieldType = TransferField.AMOUNT,
                        mode = AmountMode.SINGLE_COLUMN,
                        amountColumnName = "Amount Processed",
                    ),
                // The settled currency, which no column names: a fixed value read off a column every row has.
                TransferField.CURRENCY to
                    CurrencyLookupMapping(
                        fieldType = TransferField.CURRENCY,
                        value = ValueExpr(listOf("Amount Processed"), Extraction(pattern = "^.*$", outputTemplate = "GBP")),
                    ),
                TransferField.TIMEZONE to
                    HardCodedTimezoneMapping(
                        fieldType = TransferField.TIMEZONE,
                        timezoneId = "UTC",
                    ),
            )
        val topUp =
            arrayOf(
                fixedNaming("Service Abbreviation", CRYPTO_COM_CARD_TOP_UP_ACCOUNT, unidentified = true),
                naming("Service Abbreviation"),
            )
        val rules =
            listOf(
                // Already signed: negative = spend, positive (loads/refunds) flows in. The requested currency's
                // column header has a trailing space in the source workbook.
                incomingWhenPositive("Amount Processed"),
                rule(RuleAction.Foreign(ForeignAmount(amount = ValueExpr.of("Amount Requested"), currency = ValueExpr.of("Currency ")))),
                // Fixed-name source so this strategy resolves the same Card account as the CSV strategy above
                // (both are exports of the same physical card).
                accounts(AccountSide.SOURCE, fixedNaming("Service Abbreviation", CRYPTO_COM_CARD_ACCOUNT), naming("Service Abbreviation")),
                // Rows with no card acceptor name at all ("Batch Credit Funds Transfer", "Balance transfer", …)
                // are account-level credits, not merchant purchases, as are external loads: the top-up
                // placeholder, not a merchant.
                accounts(AccountSide.TARGET, *topUp, conditions = listOf(Condition("Card Acceptor Name", ConditionOp.BLANK))),
                accounts(
                    AccountSide.TARGET,
                    *topUp,
                    conditions = listOf(Condition("Service Abbreviation", ConditionOp.EQUALS, value = "LdExtDbCr")),
                ),
                accounts(
                    AccountSide.TARGET,
                    naming("Card Acceptor Name", pattern = CARD_ACCEPTOR_NAME_TRIM_PATTERN, name = "$1", fallbackName = "Unknown Merchant"),
                    naming("Card Acceptor Name"),
                ),
            )
        return CsvImportStrategy(
            id = CsvImportStrategyId(cryptoComCardXlsxStrategyId),
            name = "Crypto.com Card (Excel)",
            config =
                CsvStrategyConfig(
                    identificationColumns =
                        setOf(
                            "Transaction Date",
                            "Transaction Time",
                            "Service Abbreviation",
                            "Card Acceptor Name",
                            "Description",
                            "Merchant Category Code",
                            "Amount Processed",
                            "Available Balance",
                            "Currency ",
                            "Amount Requested",
                        ),
                    fieldMappings = fieldMappings,
                    rules = rules,
                    fileNamePattern = "Card Transaction History",
                    // Same physical card as the CSV export above, so the two overlap wherever their date ranges do.
                    crossSourceReconcileWindow = SettlementWindow.Elapsed(CRYPTO_COM_RECONCILE_WINDOW_SECONDS),
                ),
            worksheetName = "Sheet1",
            createdAt = now,
            updatedAt = now,
        )
    }

    /**
     * Built-in strategy for crypto.com's fiat_transactions_record_*.csv export (the fiat "Cash"
     * wallet). The sign of the amount does NOT encode direction — the Transaction Kind does:
     *
     * - viban_deposit ("GBP Deposit (via FPS)", positive): external bank → Cash.
     * - viban_withdrawal ("GBP Withdrawal (via FPS)", negative): Cash → external bank.
     * - viban_card_top_up ("Top Up Card", POSITIVE but money leaves Cash): Cash → Card. A row rule
     *   flips source/target to cancel the positive-amount flip.
     * - viban_purchase ("GBP -> TGBP", a buy) and crypto_viban ("TGBP -> GBP", a sell) are cross-asset
     *   conversions → cross-asset TRADES: the debited (Currency) and credited (To Currency) legs already
     *   name the assets, and the positive-amount flip puts the debit leg on the source side, so both
     *   directions come out correct with no column swap.
     *
     * Amount/currency read the real Currency/Amount columns; a To/From Currency that is not a fiat code
     * (e.g. TGBP, or any crypto) is provisioned on demand as a crypto asset (see
     * CsvImportApplier.ensureCryptoAssets) and held in the single Crypto.com account. To Currency is
     * populated on every row; conversions are detected by Currency != To Currency.
     */
    fun buildCryptoComFiatStrategy(now: Instant): CsvImportStrategy {
        val fieldMappings =
            mapOf(
                accountFromRules(TransferField.SOURCE_ACCOUNT),
                accountFromRules(TransferField.TARGET_ACCOUNT),
                TransferField.TIMESTAMP to
                    DateTimeParsingMapping(
                        fieldType = TransferField.TIMESTAMP,
                        dateColumnName = "Timestamp (UTC)",
                        dateFormat = "yyyy-MM-dd",
                        dateTimeFormat = "yyyy-MM-dd HH:mm:ss",
                    ),
                TransferField.DESCRIPTION to
                    DirectColumnMapping(fieldType = TransferField.DESCRIPTION, value = ValueExpr(listOf("Transaction Description"))),
                // Debit leg in its real currency (== Native GBP for fiat rows, but the actual asset for
                // a crypto buy). When To Currency is a different, resolvable asset the row becomes a trade.
                TransferField.AMOUNT to
                    AmountParsingMapping(
                        fieldType = TransferField.AMOUNT,
                        mode = AmountMode.SINGLE_COLUMN,
                        amountColumnName = "Amount",
                    ),
                TransferField.CURRENCY to
                    CurrencyLookupMapping(fieldType = TransferField.CURRENCY, value = ValueExpr(listOf("Currency"))),
                // Credit leg of a conversion → the importer emits a trade when To Currency differs from
                // Currency. Any non-fiat To/From Currency (TGBP and every real crypto) is created on
                // demand as a crypto asset, so the conversion becomes a trade.
                TransferField.TO_AMOUNT to
                    AmountParsingMapping(
                        fieldType = TransferField.TO_AMOUNT,
                        mode = AmountMode.SINGLE_COLUMN,
                        amountColumnName = "To Amount",
                    ),
                TransferField.TO_CURRENCY to
                    CurrencyLookupMapping(fieldType = TransferField.TO_CURRENCY, value = ValueExpr(listOf("To Currency"))),
                TransferField.TIMEZONE to
                    HardCodedTimezoneMapping(
                        fieldType = TransferField.TIMEZONE,
                        timezoneId = "UTC",
                    ),
            )
        val rules =
            listOf(
                attribute("Transaction Kind", "cryptocom-kind"),
                attribute("Currency", "cryptocom-txn-currency"),
                attribute("Amount", "cryptocom-txn-amount"),
                attribute("To Currency", "cryptocom-to-currency"),
                attribute("To Amount", "cryptocom-to-amount"),
                attribute("Native Amount (in USD)", "cryptocom-usd-amount"),
                attribute("Transaction Hash", "cryptocom-hash"),
                incomingWhenPositive("Amount"),
                // crypto_viban ("TGBP -> GBP", a crypto sale) is a cross-asset conversion → a trade. Its
                // Currency/To Currency already name the sold/received assets, and the positive-amount flip puts
                // the crypto (source) leg on the debit side, so the trade comes out correctly directed WITHOUT
                // a column swap (which was a single-asset-transfer artifact).
                // Top-ups and purchases are positive but money LEAVES Cash: flipping source/target here
                // cancels the positive-amount flip, keeping Cash as the source.
                rule(RuleAction.Flip, conditions = listOf(Condition("Transaction Kind", ConditionOp.EQUALS, value = "viban_card_top_up"))),
                rule(RuleAction.Flip, conditions = listOf(Condition("Transaction Kind", ConditionOp.EQUALS, value = "viban_purchase"))),
                accounts(
                    AccountSide.SOURCE,
                    fixedNaming("Transaction Description", CRYPTO_COM_CASH_ACCOUNT),
                    naming("Transaction Description"),
                ),
                accounts(
                    AccountSide.TARGET,
                    fixedNaming("Transaction Description", CRYPTO_COM_CRYPTO_ACCOUNT),
                    naming("Transaction Description"),
                    conditions = listOf(Condition("Currency", ConditionOp.NOT_EQUALS_PATH, otherPath = "To Currency")),
                ),
                accounts(
                    AccountSide.TARGET,
                    // Card top-ups route to the Card account (the card export's "GBP Deposit" side of the same
                    // movement).
                    naming("Transaction Description", pattern = "^Top Up Card$", name = CRYPTO_COM_CARD_ACCOUNT),
                    // Bank deposits/withdrawals ("GBP Deposit (via FPS)"): the export names the movement, never
                    // the bank behind it. Keep the description as the placeholder account's name (so nothing is
                    // renamed) but mark it unidentified, so a bank export that DOES name both ends — resolving
                    // this wallet by sort code + account number — reconciles instead of double-counting the
                    // deposit.
                    naming(
                        "Transaction Description",
                        pattern = "^[A-Z]{3,5} (?:Deposit|Withdrawal) \\(via [A-Za-z ]+\\)$",
                        name = "$0",
                        fallbackName = "Bank Transfer",
                        unidentified = true,
                    ),
                    naming("Transaction Description"),
                ),
            )
        return CsvImportStrategy(
            id = CsvImportStrategyId(cryptoComFiatStrategyId),
            name = "Crypto.com Fiat",
            config =
                CsvStrategyConfig(
                    identificationColumns = cryptoComIdentificationColumns,
                    fieldMappings = fieldMappings,
                    rules = rules,
                    contentMatchRules = listOf(Condition("Transaction Kind", ConditionOp.MATCHES, "^(viban_|crypto_viban)")),
                    fileNamePattern = "^fiat_transactions_record_",
                    crossSourceReconcileWindow = SettlementWindow.Elapsed(CRYPTO_COM_RECONCILE_WINDOW_SECONDS),
                ),
            createdAt = now,
            updatedAt = now,
        )
    }

    /**
     * Built-in strategy for crypto.com's crypto_transactions_record_*.csv export — the crypto-native
     * ledger (card cashback, staking/earn rewards, swaps). Unlike the card/fiat strategies (which
     * denominate everything in the Native GBP columns and keep the crypto figure only as an
     * attribute), this one denominates each row in its **real Currency/Amount**, so the account holds a
     * genuine crypto balance (e.g. 0.37 CRO). Every row lands in the single [CRYPTO_COM_CRYPTO_ACCOUNT]
     * account (one balance per asset), and the crypto asset itself is created on demand during import
     * (see CsvImportApplier.ensureCryptoAssets; any non-fiat currency-lookup value becomes a crypto asset).
     *
     * The Crypto.com account is the SOURCE and the counterparty (from the description, e.g. "Card
     * Cashback") the TARGET; an amount-sign direction rule then makes a positive amount
     * (rewards) flow INTO the account and a negative amount (a spend/swap out) flow OUT of it —
     * mirroring the card strategy's sign convention.
     *
     * Cross-asset rows (To Currency present and different from Currency) become TRADES, exactly like
     * the fiat strategy's viban conversions:
     *
     * - crypto_exchange ("TGBP -> CRO"): a crypto→crypto exchange inside the App wallet — a
     *   same-account trade Crypto.com(sold) → Crypto.com(bought).
     * - viban_purchase ("GBP -> TGBP", fiat buy) debits the Cash account and credits Crypto.com;
     *   crypto_viban_exchange ("TGBP -> GBP", sell) debits Crypto.com and credits Cash. Both events
     *   ALSO appear in the fiat export (kinds viban_purchase/crypto_viban) with identical
     *   timestamp/description/amounts, so routing them to the same accounts here makes both files
     *   produce byte-identical trades — createTrade's exact-match idempotency then books each event
     *   once regardless of which file imports first. (A UI source-account override bypasses the
     *   kind-based source rule and would defeat that dedupe; built-ins assume no override.)
     *
     * All cross-asset rows carry a NEGATIVE Amount (the debited leg), so flip-on-positive never fires
     * for them and the debit stays on the source side.
     */
    fun buildCryptoComCryptoStrategy(now: Instant): CsvImportStrategy {
        val fieldMappings =
            mapOf(
                accountFromRules(TransferField.SOURCE_ACCOUNT),
                accountFromRules(TransferField.TARGET_ACCOUNT),
                TransferField.TIMESTAMP to
                    DateTimeParsingMapping(
                        fieldType = TransferField.TIMESTAMP,
                        dateColumnName = "Timestamp (UTC)",
                        dateFormat = "yyyy-MM-dd",
                        dateTimeFormat = "yyyy-MM-dd HH:mm:ss",
                    ),
                TransferField.DESCRIPTION to
                    DirectColumnMapping(fieldType = TransferField.DESCRIPTION, value = ValueExpr(listOf("Transaction Description"))),
                TransferField.AMOUNT to
                    AmountParsingMapping(
                        fieldType = TransferField.AMOUNT,
                        mode = AmountMode.SINGLE_COLUMN,
                        amountColumnName = "Amount",
                    ),
                // The row's real asset (crypto ticker or, for the odd fiat row, a currency code).
                TransferField.CURRENCY to
                    CurrencyLookupMapping(fieldType = TransferField.CURRENCY, value = ValueExpr(listOf("Currency"))),
                TransferField.TIMEZONE to
                    HardCodedTimezoneMapping(
                        fieldType = TransferField.TIMEZONE,
                        timezoneId = "UTC",
                    ),
                // Credit leg of a cross-asset row → the importer emits a trade when To Currency differs
                // from Currency (blank To Currency rows are unaffected). Any non-fiat To/From Currency
                // is created on demand as a crypto asset.
                TransferField.TO_AMOUNT to
                    AmountParsingMapping(
                        fieldType = TransferField.TO_AMOUNT,
                        mode = AmountMode.SINGLE_COLUMN,
                        amountColumnName = "To Amount",
                    ),
                TransferField.TO_CURRENCY to
                    CurrencyLookupMapping(fieldType = TransferField.TO_CURRENCY, value = ValueExpr(listOf("To Currency"))),
            )
        val conversionsAccount =
            RuleAction.Account(
                AccountSide.INTERMEDIATE,
                naming("Transaction Kind", name = CRYPTO_COM_CONVERSIONS_ACCOUNT),
            )
        val rules =
            listOf(
                attribute("Transaction Kind", "cryptocom-kind"),
                attribute("Native Currency", "cryptocom-native-currency"),
                attribute("Native Amount", "cryptocom-native-amount"),
                attribute("Native Amount (in USD)", "cryptocom-usd-amount"),
                attribute("Transaction Hash", "cryptocom-hash"),
                // Positive = crypto received into the wallet.
                incomingWhenPositive("Amount"),
                // All crypto lands in the single "Crypto.com" account (one balance per asset), not a separate
                // wallet per ticker — except viban_purchase, whose debited leg is the fiat balance held in the
                // Cash account. The "^" naming routes every other row to the Crypto.com account.
                accounts(
                    AccountSide.SOURCE,
                    naming("Transaction Kind", pattern = "^viban_purchase$", name = CRYPTO_COM_CASH_ACCOUNT),
                    fixedNaming("Transaction Kind", CRYPTO_COM_CRYPTO_ACCOUNT),
                    naming("Transaction Kind"),
                ),
                // Cross-asset rows (a trade's credited leg) route to the account holding the credited asset:
                // fiat from a sell goes to the Cash account, crypto stays in Crypto.com. The NOT_BLANK
                // condition matters: most rows have a BLANK To Currency and must keep the single-asset routing
                // below.
                accounts(
                    AccountSide.TARGET,
                    naming("Transaction Kind", pattern = "^crypto_viban_exchange$", name = CRYPTO_COM_CASH_ACCOUNT),
                    fixedNaming("Transaction Kind", CRYPTO_COM_CRYPTO_ACCOUNT),
                    naming("Transaction Kind"),
                    conditions =
                        listOf(
                            Condition("To Currency", ConditionOp.NOT_BLANK),
                            Condition("Currency", ConditionOp.NOT_EQUALS_PATH, otherPath = "To Currency"),
                        ),
                ),
                // Single-asset rows keep the description-derived counterparty (reward source / merchant),
                // except App<->Exchange transfers which route to the shared "Crypto.com Exchange" account (see
                // the constant's KDoc). Both descriptions contain "App wallet"; the incoming direction turns
                // the target into the source for the "Exchange -> App wallet" direction.
                accounts(
                    AccountSide.TARGET,
                    naming("Transaction Description", pattern = "App wallet", name = CRYPTO_COM_EXCHANGE_ACCOUNT),
                    naming("Transaction Description"),
                ),
                // Dust conversions ("Convert Dust") and wallet swaps ("Balance Conversion") arrive as separate
                // *_debited/*_credited rows (only Currency/Amount populated, no To Currency). Route both legs
                // through the Conversions account and link each debit to its credit. The patterns match ONLY
                // these two families — one-sided *_credited kinds (supercharger/rewards/admin income) are
                // deliberately excluded. Debit rows are negative and credit rows positive, so the incoming
                // direction already places the Crypto.com wallet on the correct side.
                rule(
                    RuleAction.Leg(CRYPTO_COM_CONVERSION_GROUP, LegRole.Debit),
                    conversionsAccount,
                    conditions =
                        listOf(Condition("Transaction Kind", ConditionOp.MATCHES, "^(dust_conversion|crypto_wallet_swap)_debited$")),
                ),
                rule(
                    RuleAction.Leg(CRYPTO_COM_CONVERSION_GROUP, LegRole.Credit),
                    conversionsAccount,
                    conditions =
                        listOf(Condition("Transaction Kind", ConditionOp.MATCHES, "^(dust_conversion|crypto_wallet_swap)_credited$")),
                ),
            )
        return CsvImportStrategy(
            id = CsvImportStrategyId(cryptoComCryptoStrategyId),
            name = "Crypto.com Crypto",
            config =
                CsvStrategyConfig(
                    identificationColumns = cryptoComIdentificationColumns,
                    fieldMappings = fieldMappings,
                    rules = rules,
                    fileNamePattern = "^crypto_transactions_record_",
                    crossSourceReconcileWindow = SettlementWindow.Elapsed(CRYPTO_COM_RECONCILE_WINDOW_SECONDS),
                    legGroups =
                        listOf(
                            LegGroupRule(
                                name = CRYPTO_COM_CONVERSION_GROUP,
                                // Group 1 (dust_conversion | crypto_wallet_swap) keeps the two families from pairing
                                // across each other; the time window then separates individual events within a family.
                                key =
                                    listOf(
                                        ValueExpr(listOf("Transaction Kind"), Extraction("^(dust_conversion|crypto_wallet_swap)_", "$1")),
                                    ),
                                windowSeconds = CRYPTO_COM_CONVERSION_PAIRING_WINDOW_SECONDS,
                                assembly = LegAssembly.ThroughAccount(relationshipTypeName = "conversion"),
                            ),
                        ),
                ),
            createdAt = now,
            updatedAt = now,
        )
    }
}

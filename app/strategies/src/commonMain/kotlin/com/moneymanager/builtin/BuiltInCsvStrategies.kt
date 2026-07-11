@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlin.time.ExperimentalTime::class)

package com.moneymanager.builtin

import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.csvstrategy.AccountLookupMapping
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.AttributeColumnMapping
import com.moneymanager.domain.model.csvstrategy.ColumnExtraction
import com.moneymanager.domain.model.csvstrategy.ColumnPairSwap
import com.moneymanager.domain.model.csvstrategy.CompanionTransactionRule
import com.moneymanager.domain.model.csvstrategy.ConditionalAccountMapping
import com.moneymanager.domain.model.csvstrategy.ContentMatchRule
import com.moneymanager.domain.model.csvstrategy.ConversionConfig
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMappingId
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.RegexAccountMapping
import com.moneymanager.domain.model.csvstrategy.RegexRule
import com.moneymanager.domain.model.csvstrategy.RowCondition
import com.moneymanager.domain.model.csvstrategy.RowConditionOperator
import com.moneymanager.domain.model.csvstrategy.RowPreprocessingRule
import com.moneymanager.domain.model.csvstrategy.TemplateAccountMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.qif.QifColumns
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Built-in CSV/QIF import strategy definitions. db-free; the catalog artifacts are exported from these. */
object BuiltInCsvStrategies {
    /** Fixed UUIDs for the built-in CSV/QIF strategies so they can be referenced reliably. */
    val wiseCsvStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000003")
    val monzoCsvStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000004")
    val qifStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000005")
    val santanderQifStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000006")
    val cryptoComCardStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000007")
    val cryptoComFiatStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000008")
    val cryptoComCryptoStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000009")

    /** Fixed account names shared by the crypto.com Card and Fiat strategies, so both files resolve the same accounts. */
    private const val CRYPTO_COM_CARD_ACCOUNT = "Crypto.com Card"
    private const val CRYPTO_COM_CASH_ACCOUNT = "Crypto.com Cash"

    /** Single account that holds every crypto.com crypto balance (one per-asset balance, not per account). */
    private const val CRYPTO_COM_CRYPTO_ACCOUNT = "Crypto.com"

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
     * meaningless) mixed-asset residual is isolated in this one account. See [ConversionConfig].
     */
    private const val CRYPTO_COM_CONVERSIONS_ACCOUNT = "Crypto.com Conversions"

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

    /** Account name prefix shared with the Wise API import strategy ("Wise: " + currency code). */
    private const val WISE_ACCOUNT_PREFIX = "Wise: "

    private fun builtInCsvMappingId(
        strategyGroup: Int,
        index: Int,
    ): FieldMappingId =
        FieldMappingId(
            Uuid.parse(
                "00000000-0000-0000-${strategyGroup.toString().padStart(4, '0')}-${index.toString().padStart(12, '0')}",
            ),
        )

    private fun wiseCsvMappingId(index: Int): FieldMappingId = builtInCsvMappingId(strategyGroup = 1, index = index)

    private fun monzoCsvMappingId(index: Int): FieldMappingId = builtInCsvMappingId(strategyGroup = 2, index = index)

    private fun qifMappingId(index: Int): FieldMappingId = builtInCsvMappingId(strategyGroup = 3, index = index)

    private fun santanderQifMappingId(index: Int): FieldMappingId = builtInCsvMappingId(strategyGroup = 4, index = index)

    private fun cryptoComCardMappingId(index: Int): FieldMappingId = builtInCsvMappingId(strategyGroup = 5, index = index)

    private fun cryptoComFiatMappingId(index: Int): FieldMappingId = builtInCsvMappingId(strategyGroup = 6, index = index)

    private fun cryptoComCryptoMappingId(index: Int): FieldMappingId = builtInCsvMappingId(strategyGroup = 7, index = index)

    /**
     * All built-in CSV import strategies seeded into a fresh database. [qifCurrencyId] is the fixed
     * currency the QIF strategies carry (the default the QIF import dialog pre-selects); it is resolved
     * to GBP at seed time. Defaults to the first currency for callers without a resolved id (e.g. tests).
     */
    fun builtInCsvStrategies(
        now: Instant,
        qifCurrencyId: CurrencyId = CurrencyId(1),
    ): List<CsvImportStrategy> =
        listOf(
            buildQifStrategy(now, qifCurrencyId),
            buildSantanderQifStrategy(now, qifCurrencyId),
            buildWiseCsvStrategy(now),
            buildMonzoCsvStrategy(now),
            buildCryptoComCardStrategy(now),
            buildCryptoComFiatStrategy(now),
            buildCryptoComCryptoStrategy(now),
        )

    /**
     * The column header shared by all three crypto.com exports (card_transactions_record_*,
     * fiat_transactions_record_* and crypto_transactions_record_*). Because the sets are identical,
     * the crypto.com strategies rely on [CsvImportStrategy.fileNamePattern] and content rules over
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
                // Fixed-name source so the card and fiat strategies resolve the same account pair,
                // which direction-sensitive reconciliation requires. The catch-all pattern always
                // matches; users with an existing differently-named card account remap once.
                TransferField.SOURCE_ACCOUNT to
                    RegexAccountMapping(
                        id = cryptoComCardMappingId(1),
                        fieldType = TransferField.SOURCE_ACCOUNT,
                        columnName = "Transaction Description",
                        rules = listOf(RegexRule(pattern = "^", accountName = CRYPTO_COM_CARD_ACCOUNT)),
                    ),
                TransferField.TARGET_ACCOUNT to
                    RegexAccountMapping(
                        id = cryptoComCardMappingId(2),
                        fieldType = TransferField.TARGET_ACCOUNT,
                        columnName = "Transaction Description",
                        rules =
                            listOf(
                                // Top-ups ("GBP Deposit", "EUR Deposit", …) come from the Cash account.
                                RegexRule(pattern = "^[A-Z]{3,5} Deposit$", accountName = CRYPTO_COM_CASH_ACCOUNT),
                                // Pre-mid-2022 exports describe the same top-up as "GBP -> GBP" (same
                                // currency on both sides). Cross-currency arrows (e.g. "TGBP -> GBP") are
                                // conversion-funded and deliberately NOT matched — the crypto export
                                // records that movement separately.
                                RegexRule(pattern = "^([A-Z]{3,5}) -> \\1$", accountName = CRYPTO_COM_CASH_ACCOUNT),
                            ),
                        // Everything else looks up/creates a merchant account from the raw description.
                    ),
                TransferField.TIMESTAMP to
                    DateTimeParsingMapping(
                        id = cryptoComCardMappingId(3),
                        fieldType = TransferField.TIMESTAMP,
                        dateColumnName = "Timestamp (UTC)",
                        dateFormat = "yyyy-MM-dd",
                        dateTimeFormat = "yyyy-MM-dd HH:mm:ss",
                    ),
                TransferField.DESCRIPTION to
                    DirectColumnMapping(
                        id = cryptoComCardMappingId(4),
                        fieldType = TransferField.DESCRIPTION,
                        columnName = "Transaction Description",
                    ),
                // Negative = money out of the card; positive (top-ups, refunds) flows in, so flip.
                TransferField.AMOUNT to
                    AmountParsingMapping(
                        id = cryptoComCardMappingId(5),
                        fieldType = TransferField.AMOUNT,
                        mode = AmountMode.SINGLE_COLUMN,
                        amountColumnName = "Native Amount",
                        flipAccountsOnPositive = true,
                    ),
                TransferField.CURRENCY to
                    CurrencyLookupMapping(
                        id = cryptoComCardMappingId(6),
                        fieldType = TransferField.CURRENCY,
                        columnName = "Native Currency",
                    ),
                TransferField.TIMEZONE to
                    HardCodedTimezoneMapping(
                        id = cryptoComCardMappingId(7),
                        fieldType = TransferField.TIMEZONE,
                        timezoneId = "UTC",
                    ),
            )
        val attributeMappings =
            listOf(
                AttributeColumnMapping("Currency", "cryptocom-txn-currency"),
                AttributeColumnMapping("Amount", "cryptocom-txn-amount"),
                AttributeColumnMapping("Native Amount (in USD)", "cryptocom-usd-amount"),
                AttributeColumnMapping("Transaction Hash", "cryptocom-hash"),
            )
        return CsvImportStrategy(
            id = CsvImportStrategyId(cryptoComCardStrategyId),
            name = "Crypto.com Card",
            identificationColumns = cryptoComIdentificationColumns,
            fieldMappings = fieldMappings,
            attributeMappings = attributeMappings,
            contentMatchRules = listOf(ContentMatchRule(columnName = "Transaction Kind", pattern = "^$")),
            fileNamePattern = "^card_transactions_record_",
            crossSourceReconcileWindowSeconds = CRYPTO_COM_RECONCILE_WINDOW_SECONDS,
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
                TransferField.SOURCE_ACCOUNT to
                    RegexAccountMapping(
                        id = cryptoComFiatMappingId(1),
                        fieldType = TransferField.SOURCE_ACCOUNT,
                        columnName = "Transaction Description",
                        rules = listOf(RegexRule(pattern = "^", accountName = CRYPTO_COM_CASH_ACCOUNT)),
                    ),
                TransferField.TARGET_ACCOUNT to
                    ConditionalAccountMapping(
                        id = cryptoComFiatMappingId(2),
                        fieldType = TransferField.TARGET_ACCOUNT,
                        conditions =
                            listOf(
                                RowCondition("Currency", RowConditionOperator.NOT_EQUALS_COLUMN, otherColumnName = "To Currency"),
                            ),
                        // Conversion rows (GBP -> crypto/TGBP) credit the single "Crypto.com" account
                        // (the To Currency asset), not a per-currency wallet.
                        whenTrue =
                            RegexAccountMapping(
                                id = cryptoComFiatMappingId(3),
                                fieldType = TransferField.TARGET_ACCOUNT,
                                columnName = "Transaction Description",
                                rules = listOf(RegexRule(pattern = "^", accountName = CRYPTO_COM_CRYPTO_ACCOUNT)),
                            ),
                        whenFalse =
                            RegexAccountMapping(
                                id = cryptoComFiatMappingId(4),
                                fieldType = TransferField.TARGET_ACCOUNT,
                                columnName = "Transaction Description",
                                rules =
                                    listOf(
                                        // Card top-ups route to the Card account (the card export's
                                        // "GBP Deposit" side of the same movement).
                                        RegexRule(pattern = "^Top Up Card$", accountName = CRYPTO_COM_CARD_ACCOUNT),
                                    ),
                                // Deposits/withdrawals create a counterparty account from the description.
                            ),
                    ),
                TransferField.TIMESTAMP to
                    DateTimeParsingMapping(
                        id = cryptoComFiatMappingId(5),
                        fieldType = TransferField.TIMESTAMP,
                        dateColumnName = "Timestamp (UTC)",
                        dateFormat = "yyyy-MM-dd",
                        dateTimeFormat = "yyyy-MM-dd HH:mm:ss",
                    ),
                TransferField.DESCRIPTION to
                    DirectColumnMapping(
                        id = cryptoComFiatMappingId(6),
                        fieldType = TransferField.DESCRIPTION,
                        columnName = "Transaction Description",
                    ),
                // Debit leg in its real currency (== Native GBP for fiat rows, but the actual asset for
                // a crypto buy). When To Currency is a different, resolvable asset the row becomes a trade.
                TransferField.AMOUNT to
                    AmountParsingMapping(
                        id = cryptoComFiatMappingId(7),
                        fieldType = TransferField.AMOUNT,
                        mode = AmountMode.SINGLE_COLUMN,
                        amountColumnName = "Amount",
                        flipAccountsOnPositive = true,
                    ),
                TransferField.CURRENCY to
                    CurrencyLookupMapping(
                        id = cryptoComFiatMappingId(8),
                        fieldType = TransferField.CURRENCY,
                        columnName = "Currency",
                    ),
                // Credit leg of a conversion → the importer emits a trade when To Currency differs from
                // Currency. Any non-fiat To/From Currency (TGBP and every real crypto) is created on
                // demand as a crypto asset, so the conversion becomes a trade.
                TransferField.TO_AMOUNT to
                    AmountParsingMapping(
                        id = cryptoComFiatMappingId(11),
                        fieldType = TransferField.TO_AMOUNT,
                        mode = AmountMode.SINGLE_COLUMN,
                        amountColumnName = "To Amount",
                    ),
                TransferField.TO_CURRENCY to
                    CurrencyLookupMapping(
                        id = cryptoComFiatMappingId(10),
                        fieldType = TransferField.TO_CURRENCY,
                        columnName = "To Currency",
                    ),
                TransferField.TIMEZONE to
                    HardCodedTimezoneMapping(
                        id = cryptoComFiatMappingId(9),
                        fieldType = TransferField.TIMEZONE,
                        timezoneId = "UTC",
                    ),
            )
        val rowRules =
            listOf(
                // crypto_viban ("TGBP -> GBP", a crypto sale) is a cross-asset conversion → a trade.
                // Its Currency/To Currency already name the sold/received assets, and the positive-amount
                // flip puts the crypto (source) leg on the debit side, so the trade comes out correctly
                // directed WITHOUT the old column swap (which was a single-asset-transfer artifact).
                // Top-ups and purchases are positive but money LEAVES Cash: flipping source/target
                // here cancels the positive-amount flip (the two flips are XORed), keeping Cash as
                // the source.
                RowPreprocessingRule(
                    conditions =
                        listOf(RowCondition("Transaction Kind", RowConditionOperator.EQUALS_VALUE, value = "viban_card_top_up")),
                    flipSourceAndTarget = true,
                ),
                RowPreprocessingRule(
                    conditions = listOf(RowCondition("Transaction Kind", RowConditionOperator.EQUALS_VALUE, value = "viban_purchase")),
                    flipSourceAndTarget = true,
                ),
            )
        val attributeMappings =
            listOf(
                AttributeColumnMapping("Transaction Kind", "cryptocom-kind"),
                AttributeColumnMapping("Currency", "cryptocom-txn-currency"),
                AttributeColumnMapping("Amount", "cryptocom-txn-amount"),
                AttributeColumnMapping("To Currency", "cryptocom-to-currency"),
                AttributeColumnMapping("To Amount", "cryptocom-to-amount"),
                AttributeColumnMapping("Native Amount (in USD)", "cryptocom-usd-amount"),
                AttributeColumnMapping("Transaction Hash", "cryptocom-hash"),
            )
        return CsvImportStrategy(
            id = CsvImportStrategyId(cryptoComFiatStrategyId),
            name = "Crypto.com Fiat",
            identificationColumns = cryptoComIdentificationColumns,
            fieldMappings = fieldMappings,
            attributeMappings = attributeMappings,
            rowPreprocessingRules = rowRules,
            contentMatchRules = listOf(ContentMatchRule(columnName = "Transaction Kind", pattern = "^(viban_|crypto_viban)")),
            fileNamePattern = "^fiat_transactions_record_",
            crossSourceReconcileWindowSeconds = CRYPTO_COM_RECONCILE_WINDOW_SECONDS,
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
     * Cashback") the TARGET; [AmountParsingMapping.flipAccountsOnPositive] then makes a positive amount
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
                // All crypto lands in the single "Crypto.com" account (one balance per asset), not a
                // separate wallet per ticker — except viban_purchase, whose debited leg is the fiat
                // balance held in the Cash account. The "^" fallback rule routes every other row to
                // the Crypto.com account.
                TransferField.SOURCE_ACCOUNT to
                    RegexAccountMapping(
                        id = cryptoComCryptoMappingId(1),
                        fieldType = TransferField.SOURCE_ACCOUNT,
                        columnName = "Transaction Kind",
                        rules =
                            listOf(
                                RegexRule(pattern = "^viban_purchase$", accountName = CRYPTO_COM_CASH_ACCOUNT),
                                RegexRule(pattern = "^", accountName = CRYPTO_COM_CRYPTO_ACCOUNT),
                            ),
                    ),
                // Cross-asset rows (a trade's credited leg) route to the account holding the credited
                // asset: fiat from a sell goes to the Cash account, crypto stays in Crypto.com. The
                // IS_NOT_BLANK condition matters: most rows have a BLANK To Currency and must keep the
                // single-asset routing below.
                // Single-asset rows keep the description-derived counterparty (reward source / merchant),
                // except App<->Exchange transfers which route to the shared "Crypto.com Exchange" account
                // (see the constant's KDoc). Both descriptions contain "App wallet"; the flip-on-positive
                // turns the pre-flip target into the source for the "Exchange -> App wallet" direction.
                TransferField.TARGET_ACCOUNT to
                    ConditionalAccountMapping(
                        id = cryptoComCryptoMappingId(2),
                        fieldType = TransferField.TARGET_ACCOUNT,
                        conditions =
                            listOf(
                                RowCondition("To Currency", RowConditionOperator.IS_NOT_BLANK),
                                RowCondition("Currency", RowConditionOperator.NOT_EQUALS_COLUMN, otherColumnName = "To Currency"),
                            ),
                        whenTrue =
                            RegexAccountMapping(
                                id = cryptoComCryptoMappingId(10),
                                fieldType = TransferField.TARGET_ACCOUNT,
                                columnName = "Transaction Kind",
                                rules =
                                    listOf(
                                        RegexRule(pattern = "^crypto_viban_exchange$", accountName = CRYPTO_COM_CASH_ACCOUNT),
                                        RegexRule(pattern = "^", accountName = CRYPTO_COM_CRYPTO_ACCOUNT),
                                    ),
                            ),
                        whenFalse =
                            RegexAccountMapping(
                                id = cryptoComCryptoMappingId(11),
                                fieldType = TransferField.TARGET_ACCOUNT,
                                columnName = "Transaction Description",
                                rules = listOf(RegexRule(pattern = "App wallet", accountName = CRYPTO_COM_EXCHANGE_ACCOUNT)),
                            ),
                    ),
                TransferField.TIMESTAMP to
                    DateTimeParsingMapping(
                        id = cryptoComCryptoMappingId(3),
                        fieldType = TransferField.TIMESTAMP,
                        dateColumnName = "Timestamp (UTC)",
                        dateFormat = "yyyy-MM-dd",
                        dateTimeFormat = "yyyy-MM-dd HH:mm:ss",
                    ),
                TransferField.DESCRIPTION to
                    DirectColumnMapping(
                        id = cryptoComCryptoMappingId(4),
                        fieldType = TransferField.DESCRIPTION,
                        columnName = "Transaction Description",
                    ),
                // Positive = crypto received into the wallet (flip so the wallet is the target).
                TransferField.AMOUNT to
                    AmountParsingMapping(
                        id = cryptoComCryptoMappingId(5),
                        fieldType = TransferField.AMOUNT,
                        mode = AmountMode.SINGLE_COLUMN,
                        amountColumnName = "Amount",
                        flipAccountsOnPositive = true,
                    ),
                // The row's real asset (crypto ticker or, for the odd fiat row, a currency code).
                TransferField.CURRENCY to
                    CurrencyLookupMapping(
                        id = cryptoComCryptoMappingId(6),
                        fieldType = TransferField.CURRENCY,
                        columnName = "Currency",
                    ),
                TransferField.TIMEZONE to
                    HardCodedTimezoneMapping(
                        id = cryptoComCryptoMappingId(7),
                        fieldType = TransferField.TIMEZONE,
                        timezoneId = "UTC",
                    ),
                // Credit leg of a cross-asset row → the importer emits a trade when To Currency differs
                // from Currency (blank To Currency rows are unaffected). Any non-fiat To/From Currency
                // is created on demand as a crypto asset.
                TransferField.TO_AMOUNT to
                    AmountParsingMapping(
                        id = cryptoComCryptoMappingId(8),
                        fieldType = TransferField.TO_AMOUNT,
                        mode = AmountMode.SINGLE_COLUMN,
                        amountColumnName = "To Amount",
                    ),
                TransferField.TO_CURRENCY to
                    CurrencyLookupMapping(
                        id = cryptoComCryptoMappingId(9),
                        fieldType = TransferField.TO_CURRENCY,
                        columnName = "To Currency",
                    ),
            )
        val attributeMappings =
            listOf(
                AttributeColumnMapping("Transaction Kind", "cryptocom-kind"),
                AttributeColumnMapping("Native Currency", "cryptocom-native-currency"),
                AttributeColumnMapping("Native Amount", "cryptocom-native-amount"),
                AttributeColumnMapping("Native Amount (in USD)", "cryptocom-usd-amount"),
                AttributeColumnMapping("Transaction Hash", "cryptocom-hash"),
            )
        return CsvImportStrategy(
            id = CsvImportStrategyId(cryptoComCryptoStrategyId),
            name = "Crypto.com Crypto",
            identificationColumns = cryptoComIdentificationColumns,
            fieldMappings = fieldMappings,
            attributeMappings = attributeMappings,
            fileNamePattern = "^crypto_transactions_record_",
            crossSourceReconcileWindowSeconds = CRYPTO_COM_RECONCILE_WINDOW_SECONDS,
            // Dust conversions ("Convert Dust") and wallet swaps ("Balance Conversion") arrive as
            // separate *_debited/*_credited rows (only Currency/Amount populated, no To Currency). Route
            // both legs through the Conversions account and link each debit to its credit. The signal
            // patterns match ONLY these two families — one-sided *_credited kinds (supercharger/rewards/
            // admin income) are deliberately excluded. Debit rows are negative and credit rows positive,
            // so the AMOUNT flip-on-positive already places the Crypto.com wallet on the correct side.
            conversionConfig =
                ConversionConfig(
                    signalColumn = "Transaction Kind",
                    debitPattern = "^(dust_conversion|crypto_wallet_swap)_debited$",
                    creditPattern = "^(dust_conversion|crypto_wallet_swap)_credited$",
                    conversionAccountName = CRYPTO_COM_CONVERSIONS_ACCOUNT,
                    // Group 1 (dust_conversion | crypto_wallet_swap) keeps the two families from pairing
                    // across each other; the time window then separates individual events within a family.
                    pairingKeyPattern = "^(dust_conversion|crypto_wallet_swap)_",
                    pairingWindowSeconds = CRYPTO_COM_CONVERSION_PAIRING_WINDOW_SECONDS,
                    relationshipTypeName = "conversion",
                ),
            createdAt = now,
            updatedAt = now,
        )
    }

    /**
     * Builds the built-in Wise CSV import strategy for Wise's transaction-history.csv export.
     *
     * Each row carries both sides of the transaction (Source and Target columns) and a Direction
     * column saying which side is the user's Wise balance. A row preprocessing rule swaps the
     * Source/Target values for IN rows so all field mappings can read the Source-side columns,
     * then flips the resolved accounts. The per-currency Wise balance is resolved by templating
     * the currency code into the account name used by the Wise API import ("Wise: EUR").
     * Balance-to-balance conversions (same name on both sides, different currencies) route the
     * target to the other Wise balance instead of a counterparty account.
     *
     * Wise's export omits interest-earned rows, but each monthly "Assets fee" (wise-id like
     * `ACCRUAL_CHARGE-…`) implies one, so a companion transaction rule flags those transfers
     * for manual entry of the mirroring interest transfer.
     */
    fun buildWiseCsvStrategy(now: Instant): CsvImportStrategy {
        val sourceAccount =
            TemplateAccountMapping(
                id = wiseCsvMappingId(1),
                fieldType = TransferField.SOURCE_ACCOUNT,
                columnName = "Source currency",
                prefix = WISE_ACCOUNT_PREFIX,
            )
        val targetAccount =
            ConditionalAccountMapping(
                id = wiseCsvMappingId(2),
                fieldType = TransferField.TARGET_ACCOUNT,
                conditions =
                    listOf(
                        RowCondition("Source name", RowConditionOperator.EQUALS_COLUMN, otherColumnName = "Target name"),
                        RowCondition("Source name", RowConditionOperator.IS_NOT_BLANK),
                        RowCondition("Source currency", RowConditionOperator.NOT_EQUALS_COLUMN, otherColumnName = "Target currency"),
                    ),
                whenTrue =
                    TemplateAccountMapping(
                        id = wiseCsvMappingId(3),
                        fieldType = TransferField.TARGET_ACCOUNT,
                        columnName = "Target currency",
                        prefix = WISE_ACCOUNT_PREFIX,
                    ),
                whenFalse =
                    AccountLookupMapping(
                        id = wiseCsvMappingId(4),
                        fieldType = TransferField.TARGET_ACCOUNT,
                        columnName = "Target name",
                        fallbackColumns = listOf("Source name"),
                    ),
            )
        val fieldMappings =
            mapOf(
                TransferField.SOURCE_ACCOUNT to sourceAccount,
                TransferField.TARGET_ACCOUNT to targetAccount,
                TransferField.TIMESTAMP to
                    DateTimeParsingMapping(
                        id = wiseCsvMappingId(5),
                        fieldType = TransferField.TIMESTAMP,
                        dateColumnName = "Created on",
                        dateFormat = "yyyy-MM-dd",
                        dateTimeFormat = "yyyy-MM-dd HH:mm:ss",
                    ),
                TransferField.DESCRIPTION to
                    DirectColumnMapping(
                        id = wiseCsvMappingId(6),
                        fieldType = TransferField.DESCRIPTION,
                        columnName = "Reference",
                        fallbackColumns = listOf("Note", "Category", "Target name"),
                    ),
                TransferField.AMOUNT to
                    AmountParsingMapping(
                        id = wiseCsvMappingId(7),
                        fieldType = TransferField.AMOUNT,
                        mode = AmountMode.SINGLE_COLUMN,
                        amountColumnName = "Source amount (after fees)",
                        // The amount column is net of fees, but on OUT rows the fee also left the
                        // balance (e.g. ATM: 200.00 withdrawn + 7.29 fee = 207.29 debited). On IN
                        // rows the after-fees amount is already exactly what arrived.
                        feeColumnName = "Source fee amount",
                        feeConditions = listOf(RowCondition("Direction", RowConditionOperator.EQUALS_VALUE, value = "OUT")),
                    ),
                TransferField.CURRENCY to
                    CurrencyLookupMapping(
                        id = wiseCsvMappingId(8),
                        fieldType = TransferField.CURRENCY,
                        columnName = "Source currency",
                    ),
                TransferField.TIMEZONE to
                    HardCodedTimezoneMapping(
                        id = wiseCsvMappingId(9),
                        fieldType = TransferField.TIMEZONE,
                        timezoneId = "UTC",
                    ),
            )
        val rowRules =
            listOf(
                RowPreprocessingRule(
                    conditions = listOf(RowCondition("Direction", RowConditionOperator.EQUALS_VALUE, value = "IN")),
                    columnSwaps =
                        listOf(
                            ColumnPairSwap("Source name", "Target name"),
                            ColumnPairSwap("Source amount (after fees)", "Target amount (after fees)"),
                            ColumnPairSwap("Source currency", "Target currency"),
                        ),
                    flipSourceAndTarget = true,
                ),
            )
        val attributeMappings =
            listOf(
                AttributeColumnMapping("ID", "wise-id", isUniqueIdentifier = true),
                AttributeColumnMapping("Status", "wise-status"),
                AttributeColumnMapping("Direction", "wise-direction"),
                AttributeColumnMapping("Exchange rate", "wise-exchange-rate"),
                AttributeColumnMapping("Category", "wise-category"),
                AttributeColumnMapping("Note", "note"),
                AttributeColumnMapping("Created by", "wise-created-by"),
            )
        val companionRules =
            listOf(
                CompanionTransactionRule(
                    name = "Interest earned",
                    matchAttributeName = "wise-id",
                    matchValuePattern = "ACCRUAL_CHARGE-%",
                    linkAttributeName = "wise-interest-for",
                    companionDescription = "Interest earned",
                ),
            )
        val identificationColumns =
            setOf(
                "ID",
                "Status",
                "Direction",
                "Created on",
                "Finished on",
                "Source fee amount",
                "Source fee currency",
                "Target fee amount",
                "Target fee currency",
                "Source name",
                "Source amount (after fees)",
                "Source currency",
                "Target name",
                "Target amount (after fees)",
                "Target currency",
                "Exchange rate",
                "Reference",
                "Batch",
                "Created by",
                "Category",
                "Note",
            )
        return CsvImportStrategy(
            id = CsvImportStrategyId(wiseCsvStrategyId),
            name = "Wise CSV",
            identificationColumns = identificationColumns,
            fieldMappings = fieldMappings,
            attributeMappings = attributeMappings,
            rowPreprocessingRules = rowRules,
            companionTransactionRules = companionRules,
            createdAt = now,
            updatedAt = now,
        )
    }

    /**
     * Built-in strategy for QIF imports. QIF reuses the CSV strategy engine over a fixed set of
     * columns (see [QifColumns]), so its identification columns are exactly those fixed fields and
     * it matches any QIF file. The source account and currency are chosen at import time (QIF data
     * carries neither): the seeded CURRENCY mapping is a placeholder the QIF apply flow overrides.
     */
    fun buildQifStrategy(
        now: Instant,
        currencyId: CurrencyId = CurrencyId(1),
    ): CsvImportStrategy {
        val fieldMappings =
            mapOf(
                // Prefer an explicit [transfer-account]; otherwise treat the payee (then category) as
                // the other side of the transaction.
                TransferField.TARGET_ACCOUNT to
                    AccountLookupMapping(
                        id = qifMappingId(1),
                        fieldType = TransferField.TARGET_ACCOUNT,
                        columnName = QifColumns.COL_TRANSFER_ACCOUNT,
                        fallbackColumns = listOf(QifColumns.COL_PAYEE, QifColumns.COL_CATEGORY),
                    ),
                TransferField.TIMESTAMP to
                    DateTimeParsingMapping(
                        id = qifMappingId(2),
                        fieldType = TransferField.TIMESTAMP,
                        dateColumnName = QifColumns.COL_DATE,
                        dateFormat = "dd/MM/yyyy",
                    ),
                TransferField.DESCRIPTION to
                    DirectColumnMapping(
                        id = qifMappingId(3),
                        fieldType = TransferField.DESCRIPTION,
                        columnName = QifColumns.COL_PAYEE,
                        fallbackColumns = listOf(QifColumns.COL_MEMO),
                    ),
                // QIF amounts are signed: negative = money out of the account, positive = money in,
                // so positive amounts flip source/target.
                TransferField.AMOUNT to
                    AmountParsingMapping(
                        id = qifMappingId(4),
                        fieldType = TransferField.AMOUNT,
                        mode = AmountMode.SINGLE_COLUMN,
                        amountColumnName = QifColumns.COL_AMOUNT,
                        flipAccountsOnPositive = true,
                    ),
                TransferField.CURRENCY to
                    HardCodedCurrencyMapping(
                        id = qifMappingId(5),
                        fieldType = TransferField.CURRENCY,
                        // The QIF apply flow pre-selects this as the default; the user can override it.
                        currencyId = currencyId,
                    ),
                TransferField.TIMEZONE to
                    HardCodedTimezoneMapping(
                        id = qifMappingId(6),
                        fieldType = TransferField.TIMEZONE,
                        timezoneId = "UTC",
                    ),
            )
        return CsvImportStrategy(
            id = CsvImportStrategyId(qifStrategyId),
            name = "QIF",
            identificationColumns = QifColumns.headers.toSet(),
            fieldMappings = fieldMappings,
            createdAt = now,
            updatedAt = now,
        )
    }

    /**
     * Built-in QIF strategy for Santander UK statement exports. Santander packs the whole
     * transaction description into the QIF Payee field (e.g. "DIRECT DEBIT PAYMENT TO AMERICAN
     * EXPRESS REF …, MANDATE NO 0013, 1352.23"). This strategy parses it entirely via config: regex
     * rules extract the clean counterparty into the target account (flagging person-to-person payees
     * so the import also creates a Person + ownership), attribute mappings pull out the transaction
     * type / reference / mandate / posted date, and the description is cleaned of its trailing amount.
     *
     * [ContentMatchRule]s let the QIF apply flow auto-detect this strategy from the Payee content;
     * the generic [buildQifStrategy] (no content rules) remains the fallback for other banks. Like the
     * generic QIF strategy, the source account and currency are chosen at import time.
     */
    @Suppress("LongMethod")
    fun buildSantanderQifStrategy(
        now: Instant,
        currencyId: CurrencyId = CurrencyId(1),
    ): CsvImportStrategy {
        // First-match-wins rules over the Payee field; ${cp} extracts the clean counterparty name.
        // Tuned against a full Santander statement history (~7k records, ~99.9% parsed). The middle
        // rules use ".*?\bTO/\bFROM" to skip "VIA FASTER PAYMENT" / "REF.<x>" preludes that vary per row.
        val counterpartyRules =
            listOf(
                // Cashback summary lines ("1 Direct Debit Payment for Water at 1,00% Cashback, 0.36").
                RegexRule(pattern = "^\\d+ Direct Debit Payments?\\b", accountName = "Santander Cashback"),
                RegexRule(pattern = "^MY OFFERS\\b", accountName = "Santander Cashback"),
                RegexRule(pattern = "^DIRECT DEBIT INDEMNITY\\b", accountName = "Santander Direct Debit Indemnity"),
                RegexRule(pattern = "^DIRECT DEBIT REVERSAL\\b", accountName = "Santander Direct Debit Reversal"),
                RegexRule(
                    pattern = "^DIRECT DEBIT PAYMENT TO\\s+(.+?)(?:\\s+REF\\b|\\s+MANDATE\\b|,)",
                    accountName = "Santander Direct Debit",
                    accountNameTemplate = "$1",
                ),
                RegexRule(
                    pattern = "^CARD PAYMENT TO\\s+(.+?)(?:,|\\s+ON\\b)",
                    accountName = "Santander Card Payment",
                    accountNameTemplate = "$1",
                ),
                RegexRule(
                    pattern = "^FASTER PAYMENTS RECEIPT\\b.*?\\bFROM\\s+([^,]+?)\\s*,",
                    accountName = "Santander Faster Payment",
                    accountNameTemplate = "$1",
                    counterpartyIsPerson = true,
                ),
                RegexRule(
                    pattern = "^PAYM\\b.*?\\bFROM\\s+([^,]+?)\\s*(?:,|\\s+REFERENCE\\b)",
                    accountName = "Santander Paym",
                    accountNameTemplate = "$1",
                    counterpartyIsPerson = true,
                ),
                RegexRule(
                    pattern = "^PAYM\\b.*?\\bTO\\s+(.+?)(?:\\s+REFERENCE\\b|,)",
                    accountName = "Santander Paym",
                    accountNameTemplate = "$1",
                    counterpartyIsPerson = true,
                ),
                RegexRule(
                    pattern = "^BILL PAYMENT\\b.*?\\bTO\\s+(.+?)(?:\\s+REFERENCE\\b|\\s+MANDATE\\b|,)",
                    accountName = "Santander Bill Payment",
                    accountNameTemplate = "$1",
                    counterpartyIsPerson = true,
                ),
                RegexRule(
                    pattern = "^BILL PAYMENT\\b.*?\\bFROM\\s+([^,]+?)\\s*(?:,|\\s+REFERENCE\\b)",
                    accountName = "Santander Bill Payment",
                    accountNameTemplate = "$1",
                    counterpartyIsPerson = true,
                ),
                RegexRule(
                    pattern = "^PENDED\\b.*?\\bTO\\s+(.+?)(?:\\s+REFERENCE\\b|,)",
                    accountName = "Santander Pended Payment",
                    accountNameTemplate = "$1",
                    counterpartyIsPerson = true,
                ),
                RegexRule(
                    pattern = "^STANDING ORDER\\b.*?\\bTO\\s+(.+?)(?:\\s+REFERENCE\\b|\\s+MANDATE\\b|,)",
                    accountName = "Santander Standing Order",
                    accountNameTemplate = "$1",
                    counterpartyIsPerson = true,
                ),
                RegexRule(
                    pattern = "^Third party payment\\b.*?\\bto\\s+(.+?)(?:\\s+Reference\\b|,)",
                    accountName = "Santander Third Party Payment",
                    accountNameTemplate = "$1",
                    counterpartyIsPerson = true,
                ),
                RegexRule(
                    pattern = "^TRANSFER\\b.*?\\bTO\\s+(.+?)(?:\\s+REFERENCE\\b|,)",
                    accountName = "Santander Transfer",
                    accountNameTemplate = "$1",
                    counterpartyIsPerson = true,
                ),
                RegexRule(
                    pattern = "^TRANSFER\\b.*?\\bFROM\\s+([^,.]+?)\\s*(?:,|\\.|\\s+REFERENCE\\b)",
                    accountName = "Santander Transfer",
                    accountNameTemplate = "$1",
                    counterpartyIsPerson = true,
                ),
                // Cash / cheque movements consolidate to a single account each.
                RegexRule(pattern = "^CASH\\b", accountName = "Cash"),
                RegexRule(pattern = "^WITHDRAWAL\\b", accountName = "Cash"),
                RegexRule(pattern = "^CHEQUE\\b", accountName = "Cheque"),
                // Bank giro credit refs are often "<digits><NAME>"; strip leading digits to consolidate.
                RegexRule(
                    pattern = "^BANK GIRO CREDIT REF\\s+\\d*([^,]+?)\\s*,",
                    accountName = "Santander Bank Giro Credit",
                    accountNameTemplate = "$1",
                ),
                RegexRule(
                    pattern = "^CREDIT FROM\\s+(.+?)(?:\\s+ON\\b|,)",
                    accountName = "Santander Credit",
                    accountNameTemplate = "$1",
                ),
                RegexRule(pattern = "^INTEREST\\b", accountName = "Santander Interest"),
                RegexRule(pattern = "^LOAN\\b", accountName = "Santander Loan"),
                // Fixed-name rules (no template) for the various account-fee narratives.
                RegexRule(
                    pattern = "^(MONTHLY ACCOUNT FEE|MAINTAINING THE ACCOUNT|UNARRANGED|NON-STERLING|PENDED|PENDING)\\b",
                    accountName = "Santander Fees",
                ),
            )
        val fieldMappings =
            mapOf(
                TransferField.TARGET_ACCOUNT to
                    RegexAccountMapping(
                        id = santanderQifMappingId(1),
                        fieldType = TransferField.TARGET_ACCOUNT,
                        columnName = QifColumns.COL_PAYEE,
                        rules = counterpartyRules,
                        // Unmatched payees fall back to the explicit transfer-account/category like the generic QIF strategy.
                        fallbackColumns = listOf(QifColumns.COL_TRANSFER_ACCOUNT, QifColumns.COL_CATEGORY),
                    ),
                TransferField.TIMESTAMP to
                    DateTimeParsingMapping(
                        id = santanderQifMappingId(2),
                        fieldType = TransferField.TIMESTAMP,
                        dateColumnName = QifColumns.COL_DATE,
                        dateFormat = "dd/MM/yyyy",
                    ),
                TransferField.DESCRIPTION to
                    DirectColumnMapping(
                        id = santanderQifMappingId(3),
                        fieldType = TransferField.DESCRIPTION,
                        columnName = QifColumns.COL_PAYEE,
                        fallbackColumns = listOf(QifColumns.COL_MEMO),
                        // Strip the trailing ", <amount>[ GBP]" Santander repeats at the end of the payee.
                        extraction = ColumnExtraction(pattern = "^(.*?),\\s*[\\d][\\d.,]*\\s*(?:GBP)?\\s*$", outputTemplate = "$1"),
                    ),
                TransferField.AMOUNT to
                    AmountParsingMapping(
                        id = santanderQifMappingId(4),
                        fieldType = TransferField.AMOUNT,
                        mode = AmountMode.SINGLE_COLUMN,
                        amountColumnName = QifColumns.COL_AMOUNT,
                        flipAccountsOnPositive = true,
                    ),
                TransferField.CURRENCY to
                    HardCodedCurrencyMapping(
                        id = santanderQifMappingId(5),
                        fieldType = TransferField.CURRENCY,
                        // The QIF apply flow pre-selects this as the default (GBP for Santander); the
                        // user can override it.
                        currencyId = currencyId,
                    ),
                TransferField.TIMEZONE to
                    HardCodedTimezoneMapping(
                        id = santanderQifMappingId(6),
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
                AttributeColumnMapping(
                    columnName = QifColumns.COL_PAYEE,
                    attributeTypeName = "santander-transaction-type",
                    extraction = ColumnExtraction(pattern = pattern),
                    emitWhenMatched = label,
                )
            }
        val attributeMappings =
            typeMappings +
                listOf(
                    AttributeColumnMapping(
                        columnName = QifColumns.COL_PAYEE,
                        attributeTypeName = "santander-reference",
                        extraction =
                            ColumnExtraction(
                                pattern = "(?:REFERENCE|REF)\\b\\.?\\s*(.+?)\\s*(?:,|\\s+MANDATE\\b)",
                                outputTemplate = "$1",
                            ),
                    ),
                    AttributeColumnMapping(
                        columnName = QifColumns.COL_PAYEE,
                        attributeTypeName = "santander-mandate",
                        extraction = ColumnExtraction(pattern = "MANDATE NO\\s*(\\d+)", outputTemplate = "$1"),
                    ),
                    AttributeColumnMapping(
                        columnName = QifColumns.COL_PAYEE,
                        attributeTypeName = "santander-posted-date",
                        extraction = ColumnExtraction(pattern = "\\bON\\s+(\\d{2}-\\d{2}-\\d{4})", outputTemplate = "$1"),
                    ),
                )
        return CsvImportStrategy(
            id = CsvImportStrategyId(santanderQifStrategyId),
            name = "Santander (QIF)",
            identificationColumns = QifColumns.headers.toSet(),
            fieldMappings = fieldMappings,
            attributeMappings = attributeMappings,
            contentMatchRules =
                listOf(
                    ContentMatchRule(
                        columnName = QifColumns.COL_PAYEE,
                        pattern =
                            "^(CARD PAYMENT|DIRECT DEBIT|\\d+ DIRECT DEBIT|FASTER PAYMENTS RECEIPT|PAYM|BILL PAYMENT|" +
                                "STANDING ORDER|Third party payment|CASH |CHEQUE|BANK GIRO CREDIT|MONTHLY ACCOUNT FEE|" +
                                "INTEREST|MAINTAINING THE ACCOUNT|CREDIT FROM|TRANSFER)",
                    ),
                ),
            createdAt = now,
            updatedAt = now,
        )
    }

    /**
     * Builds the built-in Monzo CSV import strategy for Monzo's transaction export.
     *
     * No SOURCE_ACCOUNT mapping is seeded (account ids are database-specific); the user picks
     * the Monzo account when applying the strategy. Positive amounts flow INTO the account, so
     * flipAccountsOnPositive swaps source/target for credits. Transactions are deduplicated by
     * the Transaction ID column on re-import.
     */
    fun buildMonzoCsvStrategy(now: Instant): CsvImportStrategy {
        val fieldMappings =
            mapOf(
                TransferField.TARGET_ACCOUNT to
                    AccountLookupMapping(
                        id = monzoCsvMappingId(1),
                        fieldType = TransferField.TARGET_ACCOUNT,
                        columnName = "Name",
                        fallbackColumns = listOf("Type"),
                    ),
                TransferField.TIMESTAMP to
                    DateTimeParsingMapping(
                        id = monzoCsvMappingId(2),
                        fieldType = TransferField.TIMESTAMP,
                        dateColumnName = "Date",
                        dateFormat = "dd/MM/yyyy",
                        timeColumnName = "Time",
                        timeFormat = "HH:mm:ss",
                    ),
                TransferField.DESCRIPTION to
                    DirectColumnMapping(
                        id = monzoCsvMappingId(3),
                        fieldType = TransferField.DESCRIPTION,
                        columnName = "Description",
                        fallbackColumns = listOf("Type"),
                    ),
                TransferField.AMOUNT to
                    AmountParsingMapping(
                        id = monzoCsvMappingId(4),
                        fieldType = TransferField.AMOUNT,
                        mode = AmountMode.SINGLE_COLUMN,
                        amountColumnName = "Amount",
                        flipAccountsOnPositive = true,
                    ),
                TransferField.CURRENCY to
                    CurrencyLookupMapping(
                        id = monzoCsvMappingId(5),
                        fieldType = TransferField.CURRENCY,
                        columnName = "Currency",
                    ),
                TransferField.TIMEZONE to
                    HardCodedTimezoneMapping(
                        id = monzoCsvMappingId(6),
                        fieldType = TransferField.TIMEZONE,
                        timezoneId = "Europe/London",
                    ),
            )
        val attributeMappings =
            listOf(
                AttributeColumnMapping("Transaction ID", "Monzo Transaction ID", isUniqueIdentifier = true),
                AttributeColumnMapping("Emoji", "Emoji"),
                AttributeColumnMapping("Category", "Monzo Category"),
                AttributeColumnMapping("Notes and #tags", "Notes and #tags"),
                AttributeColumnMapping("Address", "Address"),
                AttributeColumnMapping("Receipt", "Receipt"),
                AttributeColumnMapping("Type", "Type"),
            )
        val identificationColumns =
            setOf(
                "Transaction ID",
                "Date",
                "Time",
                "Type",
                "Name",
                "Emoji",
                "Category",
                "Amount",
                "Currency",
                "Local amount",
                "Local currency",
                "Notes and #tags",
                "Address",
                "Receipt",
                "Description",
                "Category split",
                "Money Out",
                "Money In",
            )
        return CsvImportStrategy(
            id = CsvImportStrategyId(monzoCsvStrategyId),
            name = "Monzo CSV",
            identificationColumns = identificationColumns,
            fieldMappings = fieldMappings,
            attributeMappings = attributeMappings,
            createdAt = now,
            updatedAt = now,
        )
    }
}

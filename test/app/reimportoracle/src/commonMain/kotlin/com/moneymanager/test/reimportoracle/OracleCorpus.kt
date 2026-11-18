package com.moneymanager.test.reimportoracle

import com.moneymanager.domain.model.WellKnownIds

/** One source a person would import: a CSV export or a QIF statement, staged and applied as the import folders do, or an API session. */
sealed interface OracleFile {
    val fileName: String

    data class Csv(
        override val fileName: String,
        val headers: List<String>,
        val rows: List<List<String>>,
    ) : OracleFile

    data class Qif(
        override val fileName: String,
        val content: String,
    ) : OracleFile

    /**
     * One downloaded API session of the built-in strategy [strategyName]: each response staged under its request's marker
     * URL, as the download stores it. [fileName] only names the session in the oracle's messages.
     */
    data class Api(
        override val fileName: String,
        val strategyName: String,
        val responses: List<Pair<String, String>>,
    ) : OracleFile
}

/**
 * One source of a scenario: a [file] imported into [account] (the folder's account, created by hand before anything is
 * imported) or, when null, into whatever account its strategy names.
 */
data class OracleSource(
    val file: OracleFile,
    val account: String? = null,
)

/** A Manual Entries amount a person types for every record a manual-entry rule tagged [name]. */
data class OracleManualEntry(
    val name: String,
    val description: String,
    val relationshipType: String,
    val amount: String,
    val excludedFromBalances: Boolean = false,
)

/**
 * Sources that interact (they record the same movements, or one completes another), imported together.
 *
 * [changedStrategy] is the CSV strategy assertion (c) edits. [cardLast4] registers a funding card on an account before
 * anything is imported (account name to last 4). [manualEntries] are typed in by assertion (d).
 */
data class OracleScenario(
    val name: String,
    val sources: List<OracleSource>,
    val changedStrategy: String,
    val accounts: List<String> = sources.mapNotNull { it.account }.distinct(),
    val cardLast4: Map<String, String> = emptyMap(),
    val manualEntries: List<OracleManualEntry> = emptyList(),
) {
    override fun toString(): String = name
}

/**
 * The corpus: real-shaped rows from the importers' own end-to-end tests, grouped into scenarios whose sources record
 * the same money. Every scenario uses built-in strategies only.
 */
object OracleCorpus {
    val MONZO_JOINT =
        OracleScenario(
            name = "Monzo personal + joint",
            sources =
                listOf(
                    OracleSource(
                        monzoFile(
                            "MonzoDataExport_personal.csv",
                            monzoRow("tx_personal_1", "19/11/2023", "21:15:00", "Monzo Joint", "-1000.00", "To joint"),
                            monzoRow("tx_personal_2", "20/11/2023", "08:01:00", "Tesco", "-12.40", "TESCO STORES"),
                        ),
                        account = "Monzo",
                    ),
                    OracleSource(
                        monzoFile(
                            "MonzoDataExport_joint.csv",
                            monzoRow("tx_joint_1", "19/11/2023", "21:15:00", "Monzo", "1000.00", "From personal"),
                            monzoRow("tx_joint_2", "21/11/2023", "18:30:00", "Landlord", "-900.00", "RENT"),
                        ),
                        account = "Monzo Joint",
                    ),
                ),
            changedStrategy = "Monzo CSV",
        )

    val FREETRADE_MONZO =
        OracleScenario(
            name = "Freetrade ISA + Monzo (placeholder other side)",
            sources =
                listOf(
                    OracleSource(
                        OracleFile.Csv(
                            "freetrade_ISA_all_activity_max_07-11-2026.csv",
                            FREETRADE_HEADERS,
                            listOf(
                                freetradeCash("Interest", "INTEREST_FROM_CASH", "2026-02-16T00:00:00.000Z", "0.02"),
                                freetradeCash("Top up", "TOP_UP", "2026-03-01T10:28:23.178Z", "1000.00"),
                                freetradeBuy(),
                            ),
                        ),
                    ),
                    OracleSource(
                        monzoFile(
                            "MonzoDataExport.csv",
                            monzoRow("tx_ft_1", "01/03/2026", "09:00:00", "Freetrade Limited", "-1000.00", "FREETRADE"),
                            monzoRow("tx_ft_2", "28/02/2026", "09:00:00", "Landlord", "-1000.00", "RENT"),
                        ),
                        account = "Monzo",
                    ),
                ),
            changedStrategy = "Freetrade",
        )

    val AMEX_SANTANDER =
        OracleScenario(
            name = "American Express QIF + Santander QIF",
            sources =
                listOf(
                    OracleSource(
                        OracleFile.Qif(
                            "amex.qif",
                            qif(
                                AMEX_PAYMENTS.mapIndexed { i, (date, _, amount) ->
                                    qifRecord(date, amount, "PAYMENT RECEIVED - THANK YOU", "  Process Date $date", "993618$i")
                                } + qifRecord("19/03/2020", "-16.10", "SAINSBURY'S", " GOODS Process Date 19/03/2020", "9936189"),
                            ),
                        ),
                        account = "AMERICAN EXPRESS",
                    ),
                    OracleSource(
                        OracleFile.Qif(
                            "santander.qif",
                            qif(
                                AMEX_PAYMENTS.map { (_, date, amount) ->
                                    qifRecord(
                                        date,
                                        "-$amount",
                                        "DIRECT DEBIT PAYMENT TO AMERICAN EXPRESS REF 3746-935125-04002, MANDATE NO 0013, $amount",
                                    )
                                },
                            ),
                        ),
                        account = "Santander",
                    ),
                ),
            changedStrategy = "American Express (QIF)",
        )

    val REVOLUT =
        OracleScenario(
            name = "Revolut EUR + USD statements (acrossFiles)",
            sources =
                listOf(
                    OracleSource(
                        OracleFile.Csv(
                            "account-statement_eur.csv",
                            REVOLUT_HEADERS,
                            listOf(
                                revolutRow("Card Payment", "2020-05-30 15:23:24", "Deichmann", "-22.35", "EUR", "977.65"),
                                revolutRow("Exchange", "2020-06-01 09:07:19", "Exchanged to USD", "-200.00", "EUR", "777.65"),
                            ),
                        ),
                    ),
                    OracleSource(
                        OracleFile.Csv(
                            "account-statement_usd.csv",
                            REVOLUT_HEADERS,
                            listOf(
                                revolutRow("Exchange", "2020-06-01 09:07:19", "Exchanged to USD", "230.00", "USD", "230.00"),
                                revolutRow("ATM", "2020-06-02 10:00:00", "Cash withdrawal at Atm", "-50.00", "USD", "180.00", fee = "1.50"),
                            ),
                        ),
                    ),
                ),
            changedStrategy = "Revolut CSV",
        )

    val PAYPAL_CARD =
        OracleScenario(
            name = "Crypto.com card + PayPal CSV (conduit)",
            sources =
                listOf(
                    OracleSource(
                        OracleFile.Csv(
                            "card_transactions_record_20251116_103317.csv",
                            CARD_HEADERS,
                            listOf(
                                cardRow("2025-11-02 11:02:33", "Crv*Paypal *Ubertrip 3", "-21.92"),
                                cardRow("2025-10-20 20:15:04", "Crv*Paypal *Uber 40293", "-3.14"),
                                cardRow("2025-10-17 08:57:28", "Paypal *Pretportrai", "-17.39"),
                            ),
                        ),
                    ),
                    OracleSource(
                        OracleFile.Csv(
                            "2025-05-04-2026-05-03.CSV",
                            PAYPAL_HEADERS,
                            listOf(
                                payPalRow(
                                    "02/11/2025",
                                    "11:55:15",
                                    "UBER PAYMENTS UK LIMITED",
                                    "Pre-approved Payment Bill User Payment",
                                    "GBP",
                                    "-21.92",
                                    "1",
                                ),
                                payPalRow("02/11/2025", "11:55:15", "", "General Credit Card Deposit", "GBP", "21.92", "2"),
                                payPalRow(
                                    "20/10/2025",
                                    "21:15:01",
                                    "Uber Technologies, Inc",
                                    "Pre-approved Payment Bill User Payment",
                                    "USD",
                                    "-4.04",
                                    "3",
                                    "BST",
                                ),
                                payPalRow("20/10/2025", "21:15:01", "", "General Credit Card Deposit", "GBP", "3.14", "4", "BST"),
                                payPalRow("20/10/2025", "21:15:01", "", "General Currency Conversion", "GBP", "-3.14", "5", "BST"),
                                payPalRow("20/10/2025", "21:15:01", "", "General Currency Conversion", "USD", "4.04", "6", "BST"),
                                payPalRow(
                                    "17/10/2025",
                                    "09:57:32",
                                    "Pret-a-Portrait Ltd",
                                    "Express Checkout Payment",
                                    "GBP",
                                    "-17.39",
                                    "7",
                                    "BST",
                                ),
                                payPalRow("17/10/2025", "09:57:32", "", "General Credit Card Deposit", "GBP", "17.39", "8", "BST"),
                                payPalRow("10/10/2025", "07:34:03", "Frank Frumento", "Mobile Payment", "GBP", "8.00", "9", "BST"),
                                payPalRow(
                                    "10/10/2025",
                                    "08:32:34",
                                    "",
                                    "General Withdrawal",
                                    "GBP",
                                    "-8.00",
                                    "10",
                                    "BST",
                                    status = "Processing",
                                ),
                            ),
                        ),
                    ),
                ),
            changedStrategy = "PayPal CSV",
        )

    /**
     * The conduit statement against a card chain it splits (`PayPalCsvE2ETest`'s split payments): Uber authorises £21.58
     * on the card then tops up £0.27 against PayPal's one £21.85 (paid on in USD), and £107.84 less a £0.03 refund
     * against PayPal's £107.81.
     */
    val PAYPAL_SPLIT =
        OracleScenario(
            name = "Crypto.com card + PayPal CSV (split conduit)",
            sources =
                listOf(
                    OracleSource(
                        OracleFile.Csv(
                            "card_transactions_record_20251116_103317.csv",
                            CARD_HEADERS,
                            listOf(
                                cardRow("2025-10-20 19:14:56", "Crv*Paypal *Uber 40293", "-21.58"),
                                cardRow("2025-10-21 10:02:38", "Crv*Paypal *Uber 40293", "-0.27"),
                                cardRow("2025-04-08 21:32:48", "Crv*Paypal *Uber 40293", "-107.84"),
                                cardRow("2025-04-08 21:40:54", "Refund: Crv*Paypal *Uber 40293", "0.03"),
                            ),
                        ),
                    ),
                    OracleSource(
                        OracleFile.Csv(
                            "2025-05-04-2026-05-03.CSV",
                            PAYPAL_HEADERS,
                            listOf(
                                payPalRow(
                                    "20/10/2025",
                                    "20:53:44",
                                    "Uber Technologies, Inc",
                                    "Pre-approved Payment Bill User Payment",
                                    "USD",
                                    "-28.08",
                                    "s1",
                                    "BST",
                                ),
                                payPalRow("20/10/2025", "20:53:44", "", "General Credit Card Deposit", "GBP", "21.85", "s2", "BST"),
                                payPalRow("20/10/2025", "20:53:44", "", "General Currency Conversion", "GBP", "-21.85", "s3", "BST"),
                                payPalRow("20/10/2025", "20:53:44", "", "General Currency Conversion", "USD", "28.08", "s4", "BST"),
                                payPalRow(
                                    "08/04/2025",
                                    "22:39:52",
                                    "UBER PAYMENTS UK LIMITED",
                                    "Pre-approved Payment Bill User Payment",
                                    "GBP",
                                    "-107.81",
                                    "s5",
                                    "BST",
                                ),
                                payPalRow("08/04/2025", "22:39:52", "", "General Credit Card Deposit", "GBP", "107.81", "s6", "BST"),
                            ),
                        ),
                    ),
                ),
            changedStrategy = "PayPal CSV",
        )

    /**
     * PayPal's activity export and one PayPal API session of the same movements, as `PayPalImportE2ETest` stages the
     * API: a payment received with PayPal's fee on top, a payment sent, a GBP to USD conversion and a declined payment.
     * The export books into one "PayPal" account and the API into one account per balance currency, so today the two
     * record these movements on different accounts.
     */
    val PAYPAL_CSV_API =
        OracleScenario(
            name = "PayPal CSV + API session",
            sources =
                listOf(
                    OracleSource(
                        OracleFile.Csv(
                            "2025-11-01-2025-11-30.CSV",
                            PAYPAL_HEADERS,
                            listOf(
                                payPalRow(
                                    "03/11/2025",
                                    "09:00:00",
                                    "Alice Buyer",
                                    "Express Checkout Payment",
                                    "GBP",
                                    "50.00",
                                    "PAY1",
                                    fee = "-1.59",
                                    total = "48.41",
                                ),
                                payPalRow("04/11/2025", "09:00:00", "Coffee Roasters", "Mobile Payment", "GBP", "-8.00", "PAY2"),
                                payPalRow("05/11/2025", "09:00:00", "", "General Currency Conversion", "GBP", "-10.00", "CNV1"),
                                payPalRow("05/11/2025", "09:00:00", "", "General Currency Conversion", "USD", "12.50", "CNV2"),
                            ),
                        ),
                    ),
                    OracleSource(OracleFile.Api("paypal-session", "PayPal API", PAYPAL_RESPONSES)),
                ),
            changedStrategy = "PayPal CSV",
        )

    val CURVE =
        OracleScenario(
            name = "Crypto.com card + Curve export",
            sources =
                listOf(
                    OracleSource(
                        OracleFile.Csv(
                            "card_transactions_record_20231120_210200.csv",
                            CARD_HEADERS,
                            listOf(cardRow("2023-11-19 21:15:00", "Crv*Amazon", "-12.99")),
                        ),
                    ),
                    OracleSource(
                        OracleFile.Csv(
                            "Transaction History 2023-11-19.csv",
                            CURVE_HEADERS,
                            listOf(
                                listOf("1", "2023-11-19", "AMZN Mktp UK", "7721", "5999", "GBP", "12.99"),
                                listOf("2", "2023-11-18", "Mavroni", "7721", "5999", "EUR", "50.00"),
                            ),
                        ),
                    ),
                ),
            changedStrategy = "Curve CSV",
            accounts = listOf("Crypto.com Card"),
            cardLast4 = mapOf("Crypto.com Card" to "7721"),
        )

    val BINANCE_KOINLY =
        OracleScenario(
            name = "Binance CSV + Koinly (reconciliation source)",
            sources =
                listOf(
                    OracleSource(
                        OracleFile.Csv(
                            "binance.csv",
                            BINANCE_HEADERS,
                            listOf(
                                listOf("53064551", "2021-05-20 10:00:00", "Spot", "Deposit", "BTC", "0.5", ""),
                                listOf(
                                    "53064551",
                                    "2021-05-26 19:49:05",
                                    "Spot",
                                    "Withdraw",
                                    "BTC",
                                    "-0.27986608",
                                    "Withdraw fee is included",
                                ),
                            ),
                        ),
                    ),
                    OracleSource(
                        OracleFile.Csv(
                            "koinly.csv",
                            KOINLY_HEADERS,
                            listOf(
                                koinlyRow(
                                    "K0",
                                    "2021-05-20 10:00:00",
                                    "deposit",
                                    to = Triple("Binance;binance", "0.5", "BTC;1"),
                                ),
                                koinlyRow(
                                    "K2",
                                    "2021-05-22 12:00:00",
                                    "trade",
                                    from = Triple("Binance;binance", "0.1", "BTC;1"),
                                    to = Triple("Binance;binance", "4000", "USDT;2"),
                                ),
                                koinlyRow(
                                    "K1",
                                    "2021-05-26 19:48:36",
                                    "transfer",
                                    from = Triple("Binance;binance", "0.27931608", "BTC;1"),
                                    to = Triple("Nexo;nexo", "0.27931608", "BTC;1"),
                                    fee = "0.00055",
                                ),
                            ),
                        ),
                    ),
                ),
            changedStrategy = "Binance CSV",
            manualEntries =
                listOf(
                    OracleManualEntry(
                        name = "Withdrawal fee",
                        description = "Withdrawal fee",
                        relationshipType = WellKnownIds.FEE_RELATIONSHIP_TYPE_NAME,
                        amount = "0.00055",
                    ),
                ),
        )

    /**
     * Bybit's ledger exports and two downloads of its API: the first holds the deposit and the withdrawal, the second
     * (an incremental download overlapping it) the withdrawal again. Each movement is one transaction, whatever records it.
     */
    val BYBIT_CSV_API =
        OracleScenario(
            name = "Bybit CSV + overlapping API sessions",
            sources =
                listOf(
                    OracleSource(
                        OracleFile.Csv(
                            "AssetChangeDetails_spot_${BYBIT_UID}_20220101_20221231_0.csv",
                            listOf("Uid", "Type", "Coin", "Amount", "Wallet Balance", "Time(UTC)"),
                            listOf(
                                listOf(BYBIT_UID, "trade", "USDT", "-684.982144", "0.119229", "2022-01-05 13:18:18"),
                                listOf(BYBIT_UID, "trade", "KASTA", "492.8", "967.9244", "2022-01-05 13:18:18"),
                                listOf(BYBIT_UID, "tradingFee", "KASTA", "-0.4928", "967.4316", "2022-01-05 13:18:18"),
                                listOf(BYBIT_UID, "userDeposit", "USDT", "1346", "1346", "2022-01-02 14:36:12"),
                            ),
                        ),
                    ),
                    OracleSource(
                        OracleFile.Csv(
                            "AssetChangeDetails_fund_${BYBIT_UID}_20230101_20231231_0.csv",
                            listOf("Uid", "Date & Time(UTC)", "Coin", "QTY", "Type", "Account Balance", "Description"),
                            listOf(
                                listOf(BYBIT_UID, "2023-11-13 09:18:18", "BTC", "-0.00039664", "Withdraw", "0", "Withdrawal"),
                            ),
                        ),
                    ),
                    OracleSource(OracleFile.Api("bybit-session-1", "Bybit", listOf(BYBIT_DEPOSIT, BYBIT_WITHDRAWAL))),
                    OracleSource(OracleFile.Api("bybit-session-2", "Bybit", listOf(BYBIT_WITHDRAWAL))),
                ),
            changedStrategy = "Bybit Funding CSV",
        )

    /**
     * Binance's statement export and one API session of the same movements, as `BinanceCsvThenApiE2ETest` stages them:
     * a deposit the export can only give a placeholder for, a two-fill order the export folds into one trade, a Simple
     * Earn subscription, and two withdrawals the API reports net of their fee.
     */
    val BINANCE_CSV_API =
        OracleScenario(
            name = "Binance CSV + API session",
            sources =
                listOf(
                    OracleSource(
                        OracleFile.Csv(
                            "0452506e-8714-11ee-9934-06655da838d5-1.csv",
                            BINANCE_HEADERS,
                            listOf(
                                binanceRow("2023-11-14 22:13:21", "Deposit", "BTC", "0.01"),
                                binanceRow("2023-11-14 22:13:25", "Transaction Spend", "USDT", "-12000"),
                                binanceRow("2023-11-14 22:13:25", "Transaction Buy", "BTC", "0.3"),
                                binanceRow("2023-11-14 22:13:25", "Transaction Fee", "USDT", "-4"),
                                binanceRow("2023-11-14 22:13:25", "Transaction Fee", "USDT", "-8"),
                                binanceRow("2023-11-14 22:13:26", "Simple Earn Flexible Subscription", "XMR", "-5.81", "Binance Earn"),
                                binanceRow("2023-11-14 22:19:00", "Withdraw", "LINK", "-82.358", "Withdraw fee is included"),
                                binanceRow("2023-11-14 22:20:00", "Fiat Withdrawal", "GBP", "-1134.83"),
                            ),
                        ),
                    ),
                    OracleSource(OracleFile.Api("binance-session", "Binance", BINANCE_RESPONSES)),
                ),
            changedStrategy = "Binance CSV",
        )

    /**
     * Two Kraken downloads, as `KrakenExchangeApiE2ETest` stages them: the first holds two trades and a deposit, the
     * second (overlapping it) the same trades again and a withdrawal. Kraken has no CSV strategy, so (c) changes the API
     * strategy itself.
     */
    val KRAKEN_API =
        OracleScenario(
            name = "Kraken overlapping API sessions",
            sources =
                listOf(
                    OracleSource(
                        OracleFile.Api("kraken-session-1", "Kraken", listOf(KRAKEN_TRADES, KRAKEN_DEPOSITS, KRAKEN_DEPOSIT_STATUS)),
                    ),
                    OracleSource(OracleFile.Api("kraken-session-2", "Kraken", listOf(KRAKEN_TRADES, KRAKEN_WITHDRAWALS))),
                ),
            changedStrategy = "Kraken",
        )

    /**
     * Monzo's data export and two downloads of its API, all of one account: the export holds two payments, the first
     * download those two, the second (an incremental download overlapping it) the second payment again and a third.
     */
    val MONZO_CSV_API =
        OracleScenario(
            name = "Monzo CSV + overlapping API sessions",
            sources =
                listOf(
                    OracleSource(
                        monzoFile(
                            "MonzoDataExport_api.csv",
                            monzoRow("tx_api_1", "20/11/2023", "08:01:00", "Tesco", "-12.40", "TESCO STORES"),
                            monzoRow("tx_api_2", "21/11/2023", "18:30:00", "Landlord", "-900.00", "RENT"),
                        ),
                        account = "Monzo",
                    ),
                    OracleSource(
                        OracleFile.Api("monzo-session-1", "Monzo", listOf(MONZO_ACCOUNTS, monzoTransactions(MONZO_TX_1, MONZO_TX_2))),
                    ),
                    OracleSource(
                        OracleFile.Api("monzo-session-2", "Monzo", listOf(MONZO_ACCOUNTS, monzoTransactions(MONZO_TX_2, MONZO_TX_3))),
                    ),
                ),
            changedStrategy = "Monzo CSV",
        )

    /**
     * Two statements of the card that each record every payment (its QIF's and its PDF's, say): the second's
     * placeholders reconcile against the first's, which lost to the bank's records, so re-running the first alone
     * must leave the second's excluded as before.
     */
    val AMEX_TWO_STATEMENTS =
        OracleScenario(
            name = "Santander QIF + two American Express statements",
            sources =
                listOf(
                    OracleSource(OracleFile.Qif("santander.qif", santanderAmexPayments()), account = "Santander"),
                    OracleSource(OracleFile.Qif("amex.qif", amexStatement("9936181")), account = "AMERICAN EXPRESS"),
                    OracleSource(OracleFile.Qif("amex-pdf.qif", amexStatement("8836181")), account = "AMERICAN EXPRESS"),
                ),
            changedStrategy = "American Express (QIF)",
        )

    /**
     * A card movement into Curve, then two of Curve's own exports that repeat the same rows: the later export's rows
     * match the first's records, and the card's movement reconciles the per-card placeholder.
     */
    val CURVE_OVERLAP =
        OracleScenario(
            name = "Crypto.com card + two overlapping Curve exports",
            sources =
                listOf(
                    OracleSource(
                        OracleFile.Csv(
                            "card_transactions_record_20260510_100000.csv",
                            CARD_HEADERS,
                            listOf(cardRow("2026-05-02 11:05:08", "Crv*Lastpasscom London", "-32.31")),
                        ),
                    ),
                    OracleSource(OracleFile.Csv("Transactions 20260101-20260505.csv", CURVE_TRANSACTIONS_HEADERS, CURVE_TRANSACTIONS)),
                    OracleSource(
                        OracleFile.Csv("Transactions 20260101-20260601.csv", CURVE_TRANSACTIONS_HEADERS, CURVE_TRANSACTIONS.reversed()),
                    ),
                ),
            changedStrategy = "Curve CSV",
            accounts = listOf("Crypto.com Card"),
            cardLast4 = mapOf("Crypto.com Card" to "7721"),
        )

    /**
     * Two like top-ups a day apart, only the first of which the bank's payment to Freetrade explains: re-running the
     * bank's statement alone must not let the first top-up claim its twin as a duplicate of itself.
     */
    val FREETRADE_TWO_TOP_UPS =
        OracleScenario(
            name = "Monzo + Freetrade ISA with two like top-ups",
            sources =
                listOf(
                    OracleSource(
                        monzoFile(
                            "MonzoDataExport.csv",
                            monzoRow("tx_ft_1", "01/03/2026", "09:00:00", "Freetrade Limited", "-1000.00", "FREETRADE"),
                        ),
                        account = "Monzo",
                    ),
                    OracleSource(
                        OracleFile.Csv(
                            "freetrade_ISA_all_activity_max_07-11-2026.csv",
                            FREETRADE_HEADERS,
                            listOf(
                                freetradeCash("Top up", "TOP_UP", "2026-03-01T10:28:23.178Z", "1000.00"),
                                freetradeCash("Top up", "TOP_UP", "2026-03-02T10:28:23.178Z", "1000.00"),
                            ),
                        ),
                    ),
                ),
            changedStrategy = "Freetrade",
        )

    /** Two overlapping statements of one account: the later one's rows are all duplicates of the earlier one's records. */
    val SANTANDER_OVERLAP =
        OracleScenario(
            name = "Two overlapping Santander QIF statements",
            sources =
                listOf(
                    OracleSource(
                        OracleFile.Qif(
                            "santander.qif",
                            qif(listOf(SANTANDER_VANGUARD_1, SANTANDER_VANGUARD_2, SANTANDER_AMEX_DEBIT)),
                        ),
                        account = "Santander",
                    ),
                    OracleSource(
                        OracleFile.Qif("santander-later.qif", qif(listOf(SANTANDER_VANGUARD_1, SANTANDER_AMEX_DEBIT))),
                        account = "Santander",
                    ),
                ),
            changedStrategy = "Santander (QIF)",
        )

    val ALL: List<OracleScenario> =
        listOf(
            AMEX_TWO_STATEMENTS,
            CURVE_OVERLAP,
            FREETRADE_TWO_TOP_UPS,
            SANTANDER_OVERLAP,
            MONZO_JOINT,
            FREETRADE_MONZO,
            AMEX_SANTANDER,
            REVOLUT,
            PAYPAL_CARD,
            PAYPAL_SPLIT,
            PAYPAL_CSV_API,
            CURVE,
            BINANCE_KOINLY,
            BYBIT_CSV_API,
            BINANCE_CSV_API,
            KRAKEN_API,
            MONZO_CSV_API,
        )
}

private val MONZO_HEADERS =
    listOf(
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

internal fun monzoFile(
    fileName: String,
    vararg rows: List<String>,
) = OracleFile.Csv(fileName, MONZO_HEADERS, rows.toList())

internal fun monzoRow(
    id: String,
    date: String,
    time: String,
    name: String,
    amount: String,
    description: String,
): List<String> =
    listOf(id, date, time, "Faster payment", name, "", "Transfers", amount, "GBP", amount, "GBP", "", "", "", description, "", "", "")

private val FREETRADE_HEADERS =
    (
        "Title,Type,Timestamp,Account Currency,Total Amount in Account Currency,Buy / Sell,Ticker,ISIN," +
            "Price per Share in Account Currency,Stamp Duty,Quantity,Venue,Order ID,Order Type,Instrument Currency," +
            "Total Amount in Instrument Currency,Price per Share,FX Rate,Base FX Rate,FX Fee (BPS),FX Fee Amount," +
            "Dividend Ex Date,Dividend Pay Date,Dividend Eligible Quantity,Dividend Amount Per Share," +
            "Dividend Gross Distribution Amount,Dividend Net Distribution Amount,Dividend Withheld Tax Percentage," +
            "Dividend Withheld Tax Amount,Stock Split Ex Date,Stock Split Pay Date,Stock Split New ISIN," +
            "Stock Split Rate of Share Outturn From,Stock Split Rate of Share Outturn To," +
            "Stock Split Maintain Holding of Initial ISIN,Stock Split New Share Quantity," +
            "Stock Split Rate of Cash Outturn Amount,Stock Split Rate of Cash Outturn Currency," +
            "Stock Split Cash Outturn Received Amount,Stock Split Has Fractional Payout," +
            "Stock Split Rate of Fractional Payout Amount,Stock Split Rate of Fractional Payout Currency," +
            "Stock Split Fractional Payout Cash Received Amount,Stock Split Fractional Payout Cash Received Currency"
    ).split(",")

private fun freetradeRow(vararg cells: Pair<String, String>): List<String> {
    val byColumn = cells.toMap()
    return FREETRADE_HEADERS.map { byColumn[it].orEmpty() }
}

private fun freetradeCash(
    title: String,
    type: String,
    time: String,
    total: String,
) = freetradeRow(
    "Title" to title,
    "Type" to type,
    "Timestamp" to time,
    "Account Currency" to "GBP",
    "Total Amount in Account Currency" to total,
)

/** The ISA's one order: the top-up buying 202 units of a fund. */
private fun freetradeBuy() =
    freetradeRow(
        "Title" to "Example Fund",
        "Type" to "ORDER",
        "Timestamp" to "2026-03-02T08:49:09.042Z",
        "Account Currency" to "GBP",
        "Total Amount in Account Currency" to "998.64",
        "Buy / Sell" to "BUY",
        "Ticker" to "EXFD",
        "ISIN" to "GB0000000001",
        "Stamp Duty" to "0.00",
        "Quantity" to "202.00000000",
        "Venue" to "London Stock Exchange",
        "Order ID" to "ORDER2026-03-02T08:49:09.042Z",
    )

/** (Amex's date, Santander's date, amount) of each card payment. */
private val AMEX_PAYMENTS =
    listOf(
        Triple("02/02/2020", "04/02/2020", "1239.68"),
        Triple("04/03/2020", "04/03/2020", "738.42"),
        Triple("02/04/2020", "02/04/2020", "1574.62"),
        Triple("03/05/2020", "06/05/2020", "1801.13"),
    )

private fun qif(records: List<String>): String = "!Type:Bank\n" + records.joinToString("")

/** One statement of the card: every [AMEX_PAYMENTS] payment and a purchase, its references starting [referencePrefix]. */
private fun amexStatement(referencePrefix: String): String =
    qif(
        AMEX_PAYMENTS.mapIndexed { i, (date, _, amount) ->
            qifRecord(date, amount, "PAYMENT RECEIVED - THANK YOU", "  Process Date $date", "$referencePrefix$i")
        } + qifRecord("19/03/2020", "-16.10", "SAINSBURY'S", " GOODS Process Date 19/03/2020", "${referencePrefix}9"),
    )

/** The bank's direct debits paying each of [AMEX_PAYMENTS]. */
private fun santanderAmexPayments(): String =
    qif(
        AMEX_PAYMENTS.map { (_, date, amount) ->
            qifRecord(date, "-$amount", "DIRECT DEBIT PAYMENT TO AMERICAN EXPRESS REF 3746-935125-04002, MANDATE NO 0013, $amount")
        },
    )

private val SANTANDER_VANGUARD_1 =
    qifRecord("27/03/2021", "-7.32", "CARD PAYMENT TO VANGUARD,7.32 GBP, RATE 1.00/GBP ON 27-03-2021, 7.32")
private val SANTANDER_VANGUARD_2 =
    qifRecord("28/03/2021", "-10.00", "CARD PAYMENT TO VANGUARD,10.00 GBP, RATE 1.00/GBP ON 28-03-2021, 10.00")
private val SANTANDER_AMEX_DEBIT =
    qifRecord("01/03/2021", "-1352.23", "DIRECT DEBIT PAYMENT TO AMERICAN EXPRESS REF 374693512504002, MANDATE NO 0013, 1352.23")

/** The columns of Curve's newer "Transactions" export. */
private val CURVE_TRANSACTIONS_HEADERS =
    listOf(
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

/** A foreign card spend funded by card 7721, Curve Cash earned, and Curve Cash spent. */
private val CURVE_TRANSACTIONS =
    listOf(
        listOf(
            "CSV",
            "2026-05-02",
            "11:05:01",
            "Lastpass.com",
            "32.31",
            "GBP",
            "43.2",
            "USD",
            "Visa Debit",
            "7721",
            "Personal",
            "General",
            "",
            "Weekend Currency Conversion Fee: £0.48",
        ),
        listOf("CSV", "2026-05-03", "09:00:00", "Curve Cash: Deliveroo", "25", "CPT", "", "", "Curve Cash", "", "", "General", "", ""),
        listOf("CSV", "2026-05-04", "09:00:00", "Amazon", "208", "CPT", "2.08", "GBP", "Curve Cash", "", "Personal", "General", "", ""),
    )

private fun qifRecord(
    date: String,
    amount: String,
    payee: String,
    memo: String? = null,
    checkNumber: String? = null,
) = buildString {
    append("D$date\nT$amount\nP$payee\n")
    memo?.let { append("M$it\n") }
    checkNumber?.let { append("N$it\n") }
    append("^\n")
}

private val REVOLUT_HEADERS =
    listOf("Type", "Product", "Started Date", "Completed Date", "Description", "Amount", "Fee", "Currency", "State", "Balance")

private fun revolutRow(
    type: String,
    time: String,
    description: String,
    amount: String,
    currency: String,
    balance: String,
    fee: String = "0.00",
): List<String> = listOf(type, "Current", time, time, description, amount, fee, currency, "COMPLETED", balance)

private val CARD_HEADERS =
    listOf(
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

private fun cardRow(
    timestamp: String,
    description: String,
    nativeAmount: String,
): List<String> = listOf(timestamp, description, "GBP", nativeAmount, "", "", "GBP", nativeAmount, "0.0", "", "")

private val PAYPAL_HEADERS =
    listOf(
        "Date",
        "Time",
        "Time zone",
        "Name",
        "Type",
        "Status",
        "Currency",
        "Amount",
        "Fees",
        "Total",
        "Exchange Rate",
        "Receipt ID",
        "Balance",
        "Transaction ID",
        "Item Title",
    )

private fun payPalRow(
    date: String,
    time: String,
    name: String,
    type: String,
    currency: String,
    amount: String,
    id: String,
    zone: String = "GMT",
    status: String = "Completed",
    fee: String = "0.00",
    total: String = amount,
): List<String> = listOf(date, time, zone, name, type, status, currency, amount, fee, total, "", "", "0.00", id, "")

private val CURVE_HEADERS =
    listOf("", "Created Date", "Merchant Name", "Funding Card Last 4 Digits", "Merchant MCC Code", "Txn Currency", "Txn Amount")

private val BINANCE_HEADERS = listOf("User_ID", "UTC_Time", "Account", "Operation", "Coin", "Change", "Remark")

private val KOINLY_HEADERS =
    listOf(
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

/** A Koinly row; [from] and [to] are (wallet, amount, currency). */
private fun koinlyRow(
    id: String,
    date: String,
    type: String,
    from: Triple<String, String, String>? = null,
    to: Triple<String, String, String>? = null,
    fee: String? = null,
): List<String> {
    val cells =
        buildMap {
            put("ID (read-only)", id)
            put("Date (UTC)", date)
            put("Type", type)
            put("Deleted", "false")
            from?.let { (wallet, amount, currency) ->
                put("From Wallet (read-only)", wallet)
                put("From Amount", amount)
                put("From Currency", currency)
            }
            to?.let { (wallet, amount, currency) ->
                put("To Wallet (read-only)", wallet)
                put("To Amount", amount)
                put("To Currency", currency)
            }
            fee?.let {
                put("Fee Amount", it)
                put("Fee Currency", (from ?: to)?.third.orEmpty())
            }
        }
    return KOINLY_HEADERS.map { cells[it].orEmpty() }
}

private const val BYBIT_UID = "12345678"

// Bybit's real responses, down to successAt = "0" and the fetch window in the marker URL.
private val BYBIT_DEPOSIT =
    "https://api.bybit.com/v5/asset/deposit/query-record?ep=v5/asset/deposit/query-record&ws=1640736000000&we=1643327999999&pg=0" to
        """
        {"retCode":0,"retMsg":"success","result":{"rows":[{"coin":"USDT","chain":"ETH","amount":"1346",
        "txID":"0xdeposit","status":3,"toAddress":"0xbybit","tag":"","depositFee":"","successAt":"0",
        "confirmations":"30","txIndex":"200","blockHash":"","batchReleaseLimit":"-1","depositType":"0",
        "fromAddress":"","id":"3562579"}],"nextPageCursor":""},"retExtInfo":{},"time":1790930541991}
        """.trimIndent()

private val BYBIT_WITHDRAWAL =
    "https://api.bybit.com/v5/asset/withdraw/query-record?ep=v5/asset/withdraw/query-record&ws=1697760000000&we=1700351999999&pg=0" to
        """
        {"retCode":0,"retMsg":"success","result":{"rows":[{"coin":"BTC","chain":"BTC","amount":"0.00031164",
        "txID":"withdrawtx","status":"success","toAddress":"bc1qwithdrawaddr","tag":"","withdrawFee":"0.000085",
        "createTime":"1699867096000","updateTime":"1699868403000","withdrawId":"26711021","withdrawType":0}],
        "nextPageCursor":""},"retExtInfo":{},"time":1790930647825}
        """.trimIndent()

private fun binanceRow(
    time: String,
    operation: String,
    coin: String,
    change: String,
    remark: String = "",
): List<String> = listOf("53064551", time, "Spot", operation, coin, change, remark)

// Binance's API stamps every movement to the millisecond where the export truncates to the second, and reports the
// withdrawals net of the charge it books beside them.
private val BINANCE_RESPONSES =
    listOf(
        "https://api.binance.com/sapi/v1/capital/deposit/hisrec?ep=sapi/v1/capital/deposit/hisrec" to
            """
            [{"id":"d1","coin":"BTC","amount":"0.01000000","status":1,"address":"bc1qdepositaddr",
              "network":"BTC","txId":"onchain-dep-1","insertTime":1700000001450}]
            """.trimIndent(),
        "https://api.binance.com/api/v3/myTrades?ep=api/v3/myTrades&fv=BTCUSDT" to
            """
            [{"symbol":"BTCUSDT","id":28457,"orderId":1000,"price":"40000.00","qty":"0.10000000",
              "quoteQty":"4000.00","commission":"4.00000000","commissionAsset":"USDT","time":1700000005775,
              "isBuyer":true},
             {"symbol":"BTCUSDT","id":28458,"orderId":1000,"price":"40000.00","qty":"0.20000000",
              "quoteQty":"8000.00","commission":"8.00000000","commissionAsset":"USDT","time":1700000005775,
              "isBuyer":true}]
            """.trimIndent(),
        "https://api.binance.com/sapi/v1/fiat/orders?ep=sapi/v1/fiat/orders?transactionType=1" to
            """
            {"code":"000000","message":"success","data":[
              {"orderNo":"fw1","fiatCurrency":"GBP","amount":"1133.33","totalFee":"1.5","status":"Successful",
               "createTime":1700000012000}
            ],"total":1,"success":true}
            """.trimIndent(),
        "https://api.binance.com/sapi/v1/capital/withdraw/history?ep=sapi/v1/capital/withdraw/history" to
            """
            [{"id":"w1","coin":"LINK","amount":"81.10800000","transactionFee":"1.25000000","status":6,
              "address":"0xwithdrawaddr","network":"ETH","txId":"onchain-wd-1","applyTime":"2023-11-14 22:19:30"}]
            """.trimIndent(),
        "https://api.binance.com/sapi/v1/simple-earn/flexible/history/subscriptionRecord" +
            "?ep=sapi/v1/simple-earn/flexible/history/subscriptionRecord" to
            """
            {"rows":[{"purchaseId":26055,"asset":"XMR","amount":"5.81000000","time":1700000006320,"status":"SUCCESS"}],"total":1}
            """.trimIndent(),
    )

// Kraken keys its trades and ledger entries by id, and names assets by their legacy codes (XXBT, ZUSD).
private val KRAKEN_TRADES =
    "https://api.kraken.com/0/private/TradesHistory?ep=0/private/TradesHistory" to
        """
        {"error":[],"result":{"trades":{
          "TCWJEG-FL4SZ-3FKGH6":{"ordertxid":"O1","pair":"XXBTZUSD","time":1700000000.100,"type":"buy",
           "price":"40000.54","cost":"20000.27","vol":"0.5","fee":"0.05","trade_id":"t1"},
          "TCWJEG-FL4SZ-3FKGH7":{"ordertxid":"O2","pair":"XXBTZUSD","time":1700000000.200,"type":"sell",
           "price":"41000.00","cost":"4100.00","vol":"0.1","fee":"0.52","trade_id":"t2"}
        },"count":2}}
        """.trimIndent()

private val KRAKEN_DEPOSITS =
    "https://api.kraken.com/0/private/Ledgers?type=deposit?ep=0/private/Ledgers?type=deposit" to
        """
        {"error":[],"result":{"ledger":{
          "LG1":{"refid":"REF1","time":1700000001.5,"asset":"XXBT","amount":"0.01","fee":"0.0000"}
        },"count":1}}
        """.trimIndent()

private val KRAKEN_WITHDRAWALS =
    "https://api.kraken.com/0/private/Ledgers?type=withdrawal?ep=0/private/Ledgers?type=withdrawal" to
        """
        {"error":[],"result":{"ledger":{
          "LG2":{"refid":"REF2","time":1700000002.5,"asset":"ZUSD","amount":"-100.00","fee":"0.0000"}
        },"count":1}}
        """.trimIndent()

private val KRAKEN_DEPOSIT_STATUS =
    "https://api.kraken.com/0/private/DepositStatus?ep=0/private/DepositStatus" to
        """
        {"error":[],"result":[
          {"method":"Bitcoin","asset":"XBT","refid":"REF1","txid":"onchain-abc","info":"bc1qsenderaddr","amount":"0.01","time":1700000001}
        ]}
        """.trimIndent()

private const val MONZO_ACCOUNT_ID = "acc_00009ORACLE00000000001"

private val MONZO_ACCOUNTS =
    "https://api.monzo.com/accounts" to
        """
        {"accounts": [{"id": "$MONZO_ACCOUNT_ID", "closed": false, "created": "2022-01-01T00:00:00.000Z",
          "description": "user_00009ORACLE0000user", "type": "uk_retail", "currency": "GBP"}]}
        """.trimIndent()

// Monzo's amounts are in pence; its ids are the export's Transaction IDs.
private val MONZO_TX_1 =
    """
    {"id": "tx_api_1", "account_id": "$MONZO_ACCOUNT_ID", "created": "2023-11-20T08:01:00.000Z", "amount": -1240,
     "currency": "GBP", "description": "TESCO STORES", "merchant": {"name": "Tesco"}, "counterparty": {}}
    """.trimIndent()

private val MONZO_TX_2 =
    """
    {"id": "tx_api_2", "account_id": "$MONZO_ACCOUNT_ID", "created": "2023-11-21T18:30:00.000Z", "amount": -90000,
     "currency": "GBP", "description": "RENT", "merchant": null, "counterparty": {"name": "Landlord"}}
    """.trimIndent()

private val MONZO_TX_3 =
    """
    {"id": "tx_api_3", "account_id": "$MONZO_ACCOUNT_ID", "created": "2023-11-22T12:00:00.000Z", "amount": -350,
     "currency": "GBP", "description": "PRET A MANGER", "merchant": {"name": "Pret A Manger"}, "counterparty": {}}
    """.trimIndent()

private fun monzoTransactions(vararg transactions: String): Pair<String, String> =
    "https://api.monzo.com/transactions?account_id=$MONZO_ACCOUNT_ID&limit=100" to
        """{"transactions": [${transactions.joinToString(",\n")}]}"""

private const val PAYPAL_API = "https://api-m.paypal.com/v1/reporting"

private val PAYPAL_BALANCES =
    "$PAYPAL_API/balances" to
        """
        {"balances": [
          {"currency": "GBP", "primary": true, "total_balance": {"currency_code": "GBP", "value": "40.41"}},
          {"currency": "USD", "total_balance": {"currency_code": "USD", "value": "12.50"}}
        ], "account_id": "MERCHANT123", "as_of_time": "2025-11-30T00:00:00Z"}
        """.trimIndent()

/** One Transaction Search item as PayPal formats it: an offset without a colon, signed decimal amounts. */
private fun payPalItem(
    id: String,
    eventCode: String,
    timestamp: String,
    currency: String,
    amount: String,
    fee: String? = null,
    status: String = "S",
    payerName: String? = null,
): String {
    val feeJson = fee?.let { """, "fee_amount": {"currency_code": "$currency", "value": "$it"}""" }.orEmpty()
    val payerJson =
        payerName
            ?.let { """, "payer_info": {"email_address": "payer@example.com", "payer_name": {"alternate_full_name": "$it"}}""" }
            .orEmpty()
    return """
        {"transaction_info": {"transaction_id": "$id", "transaction_event_code": "$eventCode",
          "transaction_initiation_date": "$timestamp", "transaction_amount": {"currency_code": "$currency", "value": "$amount"},
          "transaction_status": "$status"$feeJson}$payerJson}
        """.trimIndent()
}

private fun payPalTransactions(
    currency: String,
    vararg items: String,
): Pair<String, String> =
    "$PAYPAL_API/transactions?transaction_currency=$currency&fields=all&balance_affecting_records_only=Y" +
        "&start_date=2025-11-01T00:00:00Z&end_date=2025-12-01T00:00:00Z&page=1&page_size=500" to
        """{"transaction_details": [${items.joinToString(",\n")}], "page": 1, "total_items": ${items.size}, "total_pages": 1}"""

private val PAYPAL_RESPONSES =
    listOf(
        PAYPAL_BALANCES,
        payPalTransactions(
            "GBP",
            payPalItem("PAY1", "T0006", "2025-11-03T09:00:00+0000", "GBP", "50.00", fee = "-1.59", payerName = "Alice Buyer"),
            payPalItem("PAY2", "T0006", "2025-11-04T09:00:00+0000", "GBP", "-8.00", payerName = "Coffee Roasters"),
            payPalItem("CNV1", "T0200", "2025-11-05T09:00:00+0000", "GBP", "-10.00"),
            payPalItem("DEN1", "T0006", "2025-11-06T09:00:00+0000", "GBP", "-99.00", status = "D", payerName = "Coffee Roasters"),
        ),
        payPalTransactions("USD", payPalItem("CNV2", "T0200", "2025-11-05T09:00:00+0000", "USD", "12.50")),
    )

@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.csv

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.csvimporter.AttributeAccountMatcher
import com.moneymanager.csvimporter.BulkImportProgress
import com.moneymanager.csvimporter.CsvBulkResult
import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.csvimporter.executeCsvReimport
import com.moneymanager.csvimporter.planCsvReimport
import com.moneymanager.database.assertBulkProgress
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.FieldMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.importengineapi.createAccount
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * End-to-end test for PayPal's activity export and its reconciliation with card statements.
 *
 * A card-funded PayPal payment is recorded twice. The card statement shows "PAYPAL *<merchant>" (here
 * behind Curve too), which the pass-through detector expands into card -> [Curve ->] PayPal -> merchant.
 * PayPal's export shows the payment to the real payee plus a "General Credit Card Deposit" that names no
 * card — and, for a foreign-currency payment, a currency conversion in between. Whichever imports first,
 * every payment must be counted exactly once: funded by the card (the card's record of the money going
 * into PayPal stays), paid to the payee PayPal names, in the currency PayPal paid in.
 */
class PayPalCsvE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    // The foreign-currency payment needs USD.
    override val seedAllCurrencies: Boolean = true

    private val now = Clock.System.now()

    private val maintenance =
        object : Maintenance {
            override suspend fun reindex(): Duration = Duration.ZERO

            override suspend fun vacuum(): Duration = Duration.ZERO

            override suspend fun analyze(): Duration = Duration.ZERO

            override suspend fun refreshMaterializedViews(): Duration = Duration.ZERO

            override suspend fun fullRefreshMaterializedViews(): Duration = Duration.ZERO
        }

    private val cardHeaders =
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
        currency: String = "GBP",
        amount: String = nativeAmount,
    ): List<String> = listOf(timestamp, description, currency, amount, "", "", "GBP", nativeAmount, "0.0", "", "")

    // Card rows taken from the real export: PayPal books each payment minutes to an hour after the card.
    private val cardFile =
        listOf(
            cardRow("2025-11-02 11:02:33", "Crv*Paypal *Ubertrip 3", "-21.92"),
            cardRow("2025-10-20 20:15:04", "Crv*Paypal *Uber 40293", "-3.14"),
            cardRow("2025-10-17 08:57:28", "Paypal *Pretportrai", "-17.39"),
        )

    private val payPalHeaders =
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
        ).map { it.removePrefix("") }

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
    ): List<String> = listOf(date, time, zone, name, type, status, currency, amount, "0.00", amount, "", "", "0.00", id, "")

    private val payPalFile =
        listOf(
            payPalRow("02/11/2025", "11:55:15", "UBER PAYMENTS UK LIMITED", "Pre-approved Payment Bill User Payment", "GBP", "-21.92", "1"),
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
            payPalRow("17/10/2025", "09:57:32", "Pret-a-Portrait Ltd", "Express Checkout Payment", "GBP", "-17.39", "7", "BST"),
            payPalRow("17/10/2025", "09:57:32", "", "General Credit Card Deposit", "GBP", "17.39", "8", "BST"),
            // Money received and sent on to the bank: no card involved, nothing to reconcile.
            payPalRow("10/10/2025", "07:34:03", "Frank Frumento", "Mobile Payment", "GBP", "8.00", "9", "BST"),
            payPalRow("10/10/2025", "08:32:34", "", "General Withdrawal", "GBP", "-8.00", "10", "BST", status = "Processing"),
        )

    private suspend fun stage(
        fileName: String,
        headers: List<String>,
        rows: List<List<String>>,
    ): CsvImport {
        val id =
            repositories.csvImportRepository.createImport(
                fileName = fileName,
                headers = headers,
                rows = rows,
                fileChecksum = "checksum-$fileName",
                fileLastModified = now,
            )
        return repositories.csvImportRepository.getImport(id).first()!!
    }

    private suspend fun applyAll(
        imports: List<CsvImport>,
        sourceAccountOverride: AccountId? = null,
    ): CsvBulkResult {
        val progress = mutableListOf<BulkImportProgress>()
        val result =
            bulkApplyCsv(
                imports = imports,
                sourceAccountOverride = sourceAccountOverride,
                strategies = repositories.csvImportStrategyRepository.getAllStrategies().first(),
                currencies = repositories.currencyRepository.getAllCurrencies().first(),
                accountMappingRepository = repositories.accountMappingRepository,
                accountRepository = repositories.accountRepository,
                csvImportRepository = repositories.csvImportRepository,
                maintenance = maintenance,
                importEngine = repositories.importEngine,
                onProgress = { progress += it },
                passThroughAccounts = repositories.passThroughAccountRepository.getAll().first(),
                cryptoRepository = repositories.cryptoRepository,
                attributeAccountMatchers = AttributeAccountMatcher.registry(repositories.accountAttributeRepository.getAll().first()),
            )
        assertBulkProgress(progress, imports.size)
        assertEquals(imports.size, result.filesImported, "every file imports")
        assertEquals(0, result.filesFailed)
        return result
    }

    private suspend fun importCard(rows: List<List<String>> = cardFile) =
        applyAll(listOf(stage("card_transactions_record_20251116_103317.csv", cardHeaders, rows)))

    private suspend fun importPayPal(rows: List<List<String>> = payPalFile) =
        applyAll(listOf(stage("2025-05-04-2026-05-03.CSV", payPalHeaders, rows)))

    private suspend fun account(name: String) =
        repositories.accountRepository
            .getAllAccounts()
            .first()
            .single { it.name == name }

    private fun Transfer.isExcluded() = attributes.any { it.attributeType.name == "excluded" }

    /** What [accountName] holds of [currency], counting only transfers and trades that move balances. */
    private suspend fun balance(
        accountName: String,
        currency: String,
    ): BigDecimal {
        val account =
            repositories.accountRepository
                .getAllAccounts()
                .first()
                .firstOrNull { it.name == accountName } ?: return BigDecimal.ZERO
        var total = BigDecimal.ZERO
        for (t in repositories.transactionRepository.getTransactionsByAccount(account.id).first()) {
            if (t.isExcluded() || t.amount.asset.code != currency) continue
            val value = t.amount.toDisplayValue()
            if (t.targetAccountId == account.id) total += value
            if (t.sourceAccountId == account.id) total -= value
        }
        for (trade in repositories.tradeRepository.getTradesByAccount(account.id).first()) {
            if (trade.fromAccountId == account.id && trade.from.asset.code == currency) total -= trade.from.toDisplayValue()
            if (trade.toAccountId == account.id && trade.to.asset.code == currency) total += trade.to.toDisplayValue()
        }
        return total
    }

    private suspend fun assertBalance(
        expected: String,
        accountName: String,
        currency: String = "GBP",
    ) = assertEquals(0, BigDecimal(expected).compareTo(balance(accountName, currency)), "$accountName $currency balance")

    /** The outcome both import orders must reach. */
    private suspend fun assertEachPaymentCountedOnce() {
        // The card paid for all three, once.
        assertBalance("-42.45", "Crypto.com Card")
        // Everything that went into PayPal went out again, in each currency.
        assertBalance("0", "PayPal")
        assertBalance("0", "PayPal", "USD")
        // PayPal names the real payees and the currency actually paid…
        assertBalance("21.92", "UBER PAYMENTS UK LIMITED")
        assertBalance("4.04", "Uber Technologies, Inc", "USD")
        assertBalance("17.39", "Pret-a-Portrait Ltd")
        // …so the card statement's guesses at them count for nothing,
        assertBalance("0", "Ubertrip 3")
        assertBalance("0", "Uber 40293")
        assertBalance("0", "Pretportrai")
        // and every card deposit PayPal recorded reconciled with the card's own record of it.
        assertBalance("0", "PayPal Card Funding")
        // The conversion is a trade on PayPal.
        val payPal =
            repositories.accountRepository
                .getAllAccounts()
                .first()
                .single { it.name == "PayPal" }
        val trade =
            repositories.tradeRepository
                .getTradesByAccount(payPal.id)
                .first()
                .single()
        assertEquals("GBP", trade.from.asset.code)
        assertEquals("USD", trade.to.asset.code)
        // Unrelated PayPal activity is untouched.
        assertBalance("-8.00", "Frank Frumento")
        assertBalance("8.00", "PayPal Bank Transfers")
    }

    private suspend fun reconciledLinks(): Int =
        repositories.transactionRepository
            .getTransactionsByAccount(
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .single { it.name == "PayPal" }
                    .id,
            ).first()
            .sumOf { t ->
                repositories.transferRelationshipRepository
                    .getByTransfer(t.id)
                    .first()
                    .count { it.relationshipType.name == "reconciled" && it.id1 == t.id }
            }

    @Test
    fun cardStatementFirst_thenPayPalExport_countsEachPaymentOnce() =
        runTest {
            importCard()
            importPayPal()
            assertEachPaymentCountedOnce()
            // Each deposit links to the card's leg into PayPal and to the spend leg out of PayPal it supersedes.
            assertEquals(6, reconciledLinks(), "each card deposit is linked to the card's legs through PayPal")
        }

    @Test
    fun payPalExportFirst_thenCardStatement_countsEachPaymentOnce() =
        runTest {
            importPayPal()
            importCard()
            assertEachPaymentCountedOnce()
            // Each chain's leg into PayPal and its superseded spend leg out of PayPal link to PayPal's deposit.
            assertEquals(6, reconciledLinks(), "each card leg through PayPal is linked to PayPal's deposit")
        }

    @Test
    fun payPalExportAlone_keepsTheUnidentifiedCardFundingVisible() =
        runTest {
            importPayPal()
            assertBalance("0", "PayPal")
            assertBalance("0", "PayPal", "USD")
            // No card import yet: the deposits wait on the placeholder for one to reconcile against.
            assertBalance("-42.45", "PayPal Card Funding")
        }

    /** Each `pass-through` link through PayPal, as (funding leg's other end, payment leg's other end), sorted. */
    private suspend fun passThroughPairs(): List<Pair<String, String>> {
        val accounts = repositories.accountRepository.getAllAccounts().first()
        val payPal = accounts.single { it.name == "PayPal" }.id
        val names = accounts.associate { it.id to it.name }
        val transfers = repositories.transactionRepository.getTransactionsByAccount(payPal).first()
        val byId = transfers.associateBy { it.id }

        fun Transfer.otherEnd() = names.getValue(if (sourceAccountId == payPal) targetAccountId else sourceAccountId)
        return transfers
            .flatMap { t ->
                repositories.transferRelationshipRepository
                    .getByTransfer(t.id)
                    .first()
                    .filter { it.relationshipType.name == "pass-through" && it.id1 == t.id }
                    .mapNotNull { link -> byId[link.id2]?.let { t.otherEnd() to it.otherEnd() } }
            }.sortedWith(compareBy({ it.first }, { it.second }))
    }

    @Test
    fun eachCardDeposit_isLinkedToThePaymentItFunded_asOneChainThroughPayPal() =
        runTest {
            val refund =
                listOf(
                    payPalRow("01/09/2025", "10:00:00", "Pret-a-Portrait Ltd", "Payment Refund", "GBP", "5.00", "r1", "BST"),
                    payPalRow("01/09/2025", "10:00:00", "", "General Credit Card Withdrawal", "GBP", "-5.00", "r2", "BST"),
                )
            importPayPal(payPalFile + refund)
            // Two card-funded payments and one refund back to the card, each linked funding -> payment. The USD
            // Uber payment was funded in GBP through a conversion and the Mobile Payment was money received:
            // neither has a same-currency, same-amount card movement, so neither is linked.
            assertEquals(
                listOf(
                    "PayPal Card Funding" to "Pret-a-Portrait Ltd",
                    "PayPal Card Funding" to "Pret-a-Portrait Ltd",
                    "PayPal Card Funding" to "UBER PAYMENTS UK LIMITED",
                ),
                passThroughPairs(),
            )
            assertBalance("0", "PayPal")
        }

    @Test
    fun cardStatementFirst_thenPayPalExport_stillLinksEachDepositToItsPayment() =
        runTest {
            importCard()
            importPayPal()
            assertEachPaymentCountedOnce()
            assertEquals(
                listOf("PayPal Card Funding" to "Pret-a-Portrait Ltd", "PayPal Card Funding" to "UBER PAYMENTS UK LIMITED"),
                passThroughPairs().filter { it.first == "PayPal Card Funding" },
            )
        }

    @Test
    fun payPalTimes_areReadInTheZoneTheirAbbreviationNames() =
        runTest {
            importPayPal()
            // "17/10/2025 09:57:32 BST" is 08:57:32 UTC; "02/11/2025 11:55:15 GMT" is UTC already.
            val payPal = account("PayPal")
            val transfers = repositories.transactionRepository.getTransactionsByAccount(payPal.id).first()
            assertEquals(
                Instant.parse("2025-10-17T08:57:32Z"),
                transfers.single { it.description == "Pret-a-Portrait Ltd" }.timestamp,
            )
            assertEquals(
                Instant.parse("2025-11-02T11:55:15Z"),
                transfers.single { it.description == "UBER PAYMENTS UK LIMITED" }.timestamp,
            )
        }

    // The card paid EUR 29 for a GBP 25.11 charge; PayPal received the EUR 29 the card sent.
    private val foreignCardFile = listOf(cardRow("2024-08-15 13:53:14", "Paypal *Jewelry", "-25.11", currency = "EUR", amount = "-29.0"))

    private val foreignPayPalFile =
        listOf(
            payPalRow("14/08/2024", "22:48:31", "Aurorabelova", "General Payment", "EUR", "-29.00", "f1", "BST"),
            payPalRow("14/08/2024", "22:48:31", "", "General Credit Card Deposit", "EUR", "29.00", "f2", "BST"),
        )

    private suspend fun assertForeignPaymentCountedOnce() {
        // The card spent GBP 25.11, converting it to the EUR 29 it paid out.
        assertBalance("-25.11", "Crypto.com Card")
        assertBalance("0", "Crypto.com Card", "EUR")
        val conversion =
            repositories.tradeRepository
                .getTradesByAccount(account("Crypto.com Card").id)
                .first()
                .single()
        assertEquals("GBP", conversion.from.asset.code)
        assertEquals("EUR", conversion.to.asset.code)
        assertBalance("0", "PayPal", "EUR")
        assertBalance("29", "Aurorabelova", "EUR")
        assertBalance("0", "Jewelry", "EUR")
        assertBalance("0", "PayPal Card Funding", "EUR")
    }

    @Test
    fun foreignCurrencyCardCharge_thenPayPalExport_countsThePaymentOnce() =
        runTest {
            importCard(foreignCardFile)
            importPayPal(foreignPayPalFile)
            assertForeignPaymentCountedOnce()
        }

    @Test
    fun payPalExport_thenForeignCurrencyCardCharge_countsThePaymentOnce() =
        runTest {
            importPayPal(foreignPayPalFile)
            importCard(foreignCardFile)
            assertForeignPaymentCountedOnce()
        }

    @Test
    fun payPalExportFirst_thenAChainThroughCurveAlone_reconcilesWithPayPalBehindCurve() =
        runTest {
            // Only the card -> Curve -> PayPal chain: PayPal is never the card row's own counterparty, so its
            // history has to be loaded for being an inner conduit.
            importPayPal(payPalFile.take(2))
            importCard(cardFile.take(1))
            assertBalance("-21.92", "Crypto.com Card")
            assertBalance("0", "Curve")
            assertBalance("0", "PayPal")
            assertBalance("21.92", "UBER PAYMENTS UK LIMITED")
            assertBalance("0", "Ubertrip 3")
            assertBalance("0", "PayPal Card Funding")
        }

    @Test
    fun monzoCsvChargeImportedAfterThePayPalExport_reconciles() =
        runTest {
            importPayPal(payPalFile.subList(6, 8))
            val monzo =
                repositories.importEngine.createAccount(
                    Account(id = AccountId(0), name = "Monzo", openingDate = Clock.System.now()),
                    Source.Manual,
                )
            val monzoHeaders =
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
            val monzoRow =
                listOf(
                    "tx_paypal_1",
                    "17/10/2025",
                    "08:50:00",
                    "Card payment",
                    "PayPal",
                    "",
                    "Shopping",
                    "-17.39",
                    "GBP",
                    "-17.39",
                    "GBP",
                    "",
                    "",
                    "",
                    "PAYPAL *PRETPORTRAI",
                    "",
                    "-17.39",
                    "",
                )
            applyAll(listOf(stage("MonzoDataExport.csv", monzoHeaders, listOf(monzoRow))), sourceAccountOverride = monzo)
            assertBalance("-17.39", "Monzo")
            assertBalance("0", "PayPal")
            assertBalance("17.39", "Pret-a-Portrait Ltd")
            assertBalance("0", "PAYPAL *PRETPORTRAI")
            assertBalance("0", "PRETPORTRAI")
            assertBalance("0", "PayPal Card Funding")
        }

    // Uber authorises one amount on the card and settles another through PayPal: £21.58 then a £0.27
    // top-up against PayPal's £21.85 (paid on in USD), and £107.84 less a £0.03 refund against £107.81.
    private val splitCardFile =
        listOf(
            cardRow("2025-10-20 19:14:56", "Crv*Paypal *Uber 40293", "-21.58"),
            cardRow("2025-10-21 10:02:38", "Crv*Paypal *Uber 40293", "-0.27"),
            cardRow("2025-04-08 21:32:48", "Crv*Paypal *Uber 40293", "-107.84"),
            cardRow("2025-04-08 21:40:54", "Refund: Crv*Paypal *Uber 40293", "0.03"),
        )

    private val splitPayPalFile =
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
        )

    private suspend fun assertSplitPaymentsCountedOnce() {
        assertBalance("-129.66", "Crypto.com Card")
        assertBalance("0", "Curve")
        assertBalance("0", "PayPal")
        assertBalance("0", "PayPal", "USD")
        assertBalance("28.08", "Uber Technologies, Inc", "USD")
        assertBalance("107.81", "UBER PAYMENTS UK LIMITED")
        // The card statement's own view of the merchant counts for nothing, refund included.
        assertBalance("0", "Uber 40293")
        assertBalance("0", "PayPal Card Funding")
    }

    @Test
    fun splitCardCharges_thenPayPalExport_countEachPaymentOnce() =
        runTest {
            importCard(splitCardFile)
            importPayPal(splitPayPalFile)
            assertSplitPaymentsCountedOnce()
        }

    @Test
    fun payPalExport_thenSplitCardCharges_countEachPaymentOnce() =
        runTest {
            importPayPal(splitPayPalFile)
            importCard(splitCardFile)
            assertSplitPaymentsCountedOnce()
        }

    @Test
    fun reimportingTheCardFile_afterThePayPalExport_keepsPayPalsDeposits() =
        runTest {
            // PayPal first, so each card chain's leg into PayPal links forward (`reconciled`) to PayPal's own
            // deposit. Re-importing the card file must treat that deposit as PayPal's, not as a leg of the
            // card row to delete and rebuild.
            importPayPal()
            val card = stage("card_transactions_record_20251116_103317.csv", cardHeaders, cardFile)
            applyAll(listOf(card))
            // A strategy change that moves every row's values (here its time zone, by an hour) makes the
            // re-import delete and rebuild each card chain — the shape a real strategy update takes.
            reimport(card) { config ->
                config.copy(
                    fieldMappings =
                        config.fieldMappings +
                            (TransferField.TIMEZONE to HardCodedTimezoneMapping(TransferField.TIMEZONE, "Europe/London")),
                )
            }
            assertEachPaymentCountedOnce()
            val deposits = repositories.transactionRepository.getTransactionsByAccount(account("PayPal Card Funding").id).first()
            assertEquals(3, deposits.size, "PayPal's three card deposits survive the card file's re-import")
        }

    @Test
    fun reimportingSplitCardCharges_afterThePayPalExport_countsEachPaymentOnce() =
        runTest {
            // Each PayPal deposit is reconciled against two card rows, so no single row's rewrite explains it
            // away: re-importing must still lift its exclusion once both rows' chains are deleted.
            importPayPal(splitPayPalFile)
            val card = stage("card_transactions_record_20251116_103317.csv", cardHeaders, splitCardFile)
            applyAll(listOf(card))
            reimport(card) { config ->
                config.copy(
                    fieldMappings =
                        config.fieldMappings +
                            (TransferField.TIMEZONE to HardCodedTimezoneMapping(TransferField.TIMEZONE, "Europe/London")),
                )
            }
            assertSplitPaymentsCountedOnce()
        }

    private suspend fun reimport(
        csvImport: CsvImport,
        changeConfig: (CsvStrategyConfig<FieldMapping>) -> CsvStrategyConfig<FieldMapping>,
    ) {
        val current = repositories.csvImportRepository.getImport(csvImport.id).first()!!
        val installed =
            repositories.csvImportStrategyRepository
                .getAllStrategies()
                .first()
                .first { it.name == "Crypto.com Card" }
        val strategy = installed.copy(config = changeConfig(installed.config))
        val currencies = repositories.currencyRepository.getAllCurrencies().first()
        val passThroughAccounts = repositories.passThroughAccountRepository.getAll().first()
        val attributeMatchers = AttributeAccountMatcher.registry(repositories.accountAttributeRepository.getAll().first())
        val plan =
            planCsvReimport(
                csvImport = current,
                strategy = strategy,
                sourceAccountOverride = null,
                currencies = currencies,
                accountMappingRepository = repositories.accountMappingRepository,
                accountRepository = repositories.accountRepository,
                csvImportRepository = repositories.csvImportRepository,
                transactionRepository = repositories.transactionRepository,
                relationshipRepository = repositories.transferRelationshipRepository,
                transferSourceRepository = repositories.transferSourceRepository,
                tradeRepository = repositories.tradeRepository,
                passThroughAccounts = passThroughAccounts,
                attributeAccountMatchers = attributeMatchers,
            )
        assertTrue(plan.rewrites.isNotEmpty(), "the changed strategy rewrites the card chains")
        executeCsvReimport(
            plan = plan,
            csvImport = current,
            strategy = strategy,
            sourceAccountOverride = null,
            currencies = currencies,
            accountMappingRepository = repositories.accountMappingRepository,
            accountRepository = repositories.accountRepository,
            csvImportRepository = repositories.csvImportRepository,
            maintenance = maintenance,
            importEngine = repositories.importEngine,
            passThroughAccounts = passThroughAccounts,
            attributeAccountMatchers = attributeMatchers,
        )
    }

    @Test
    fun legacyExport_excludesNonMovementsAndTheWithdrawalEcho() =
        runTest {
            val headers = listOf("Date", "Time", "Time zone", "Name", "Type", "Status", "Currency", "Amount", "Receipt ID", "Balance")

            fun row(
                time: String,
                name: String,
                type: String,
                status: String,
                amount: String,
            ) = listOf("11/05/2019", time, "BST", name, type, status, "GBP", amount, "", "0.00")
            val legacy =
                stage(
                    "2015-01-03-2020-04-04.CSV",
                    headers,
                    listOf(
                        row("10:37:09", "Jason Tan", "Mobile Payment", "Completed", "11.95"),
                        // The withdrawal that moved the balance, then its echo two seconds later.
                        row("11:23:28", "", "General Withdrawal", "Removed", "-11.95"),
                        row("11:23:30", "", "General Withdrawal", "Completed", "-11.95"),
                        // An authorisation and its completed twin bracket the real payment.
                        row("12:00:00", "Google", "General Authorisation", "Pending", "-3.99"),
                        row("12:00:04", "", "General Credit Card Deposit", "Completed", "3.99"),
                        row("12:00:04", "Google", "Pre-approved Payment Bill User Payment", "Completed", "-3.99"),
                        row("12:00:04", "Google", "General Authorisation", "Completed", "-3.99"),
                        row("13:00:00", "", "General Credit Card Deposit", "Refused", "19.74"),
                        // A request for money, paid by the rows that follow it; it moves nothing itself.
                        row("14:00:00", "Dominik Pietrzyk", "Request Received", "Completed", "366.00"),
                        row("14:04:49", "Dominik Pietrzyk", "General Payment", "Completed", "-366.00"),
                        row("14:04:49", "", "General Credit Card Deposit", "Completed", "366.00"),
                    ),
                )
            applyAll(listOf(legacy))
            assertBalance("0", "PayPal")
            assertBalance("-11.95", "Jason Tan")
            assertBalance("11.95", "PayPal Bank Transfers")
            assertBalance("3.99", "Google")
            assertBalance("-369.99", "PayPal Card Funding")
            assertBalance("366", "Dominik Pietrzyk")
            val payPal =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .single { it.name == "PayPal" }
            val excluded =
                repositories.transactionRepository
                    .getTransactionsByAccount(payPal.id)
                    .first()
                    .filter { it.isExcluded() }
            assertEquals(5, excluded.size, "the echo, both authorisations, the refused deposit and the request are excluded")
            assertTrue(excluded.none { it.description == "Jason Tan" })
        }
}

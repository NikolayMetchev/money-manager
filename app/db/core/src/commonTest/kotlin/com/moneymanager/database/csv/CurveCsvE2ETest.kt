@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.csv

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.csvimporter.AttributeAccountMatcher
import com.moneymanager.csvimporter.BulkImportProgress
import com.moneymanager.csvimporter.CsvBulkResult
import com.moneymanager.csvimporter.addAttributeToken
import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.csvimporter.executeCsvReimport
import com.moneymanager.csvimporter.findUnmatchedFundingReferences
import com.moneymanager.csvimporter.planCsvReimport
import com.moneymanager.csvimporter.rerunFundingReconciles
import com.moneymanager.database.assertBulkProgress
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.importengineapi.getOrCreateAttributeType
import com.moneymanager.importengineapi.setAccountAttributeValue
import com.moneymanager.importengineapi.updateCsvStrategy
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
 * End-to-end test for the Curve CSV import and its interaction with the pass-through pipeline.
 *
 * Curve is a card aggregator, so a Curve payment shows up twice: once on the underlying card's
 * statement as "Crv*<merchant>" (which the pass-through detector expands into a Curve -> merchant
 * spend leg), and once in Curve's own export as a direct Curve -> merchant row. Because the merchant
 * account is derived from the merchant text on BOTH sides, the two representations line up in one of
 * three ways, all exercised here:
 *  - same merchant text  -> the Curve row is a plain (fuzzy) duplicate and is dropped;
 *  - different text mapped to the same account -> cross-source reconciliation keeps it, excluded+linked;
 *  - a foreign-currency row -> no GBP card counterpart, so it imports standalone in its own currency.
 */
class CurveCsvE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    // The foreign-currency Curve row needs its currency (EUR) to exist.
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

    // crypto.com card export headers, reused to produce the underlying "Crv*<merchant>" statement row.
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
    ): List<String> = listOf(timestamp, description, "GBP", nativeAmount, "", "", "GBP", nativeAmount, "0.0", "", "")

    private val curveHeaders =
        listOf(
            "",
            "Created Date",
            "Merchant Name",
            "Funding Card Last 4 Digits",
            "Merchant MCC Code",
            "Txn Currency",
            "Txn Amount",
        )

    private fun curveRow(
        index: String,
        date: String,
        merchant: String,
        currency: String,
        amount: String,
        fundingCard: String = "7721",
    ): List<String> = listOf(index, date, merchant, fundingCard, "5999", currency, amount)

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

    private suspend fun applyAll(imports: List<CsvImport>): CsvBulkResult {
        val progress = mutableListOf<BulkImportProgress>()
        val attributeMatchers =
            AttributeAccountMatcher.registry(repositories.accountAttributeRepository.getAll().first())
        val result =
            bulkApplyCsv(
                imports = imports,
                sourceAccountOverride = null,
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
                attributeAccountMatchers = attributeMatchers,
            )
        assertBulkProgress(progress, imports.size)
        return result
    }

    /** Registers a card last-4 (or space-separated list) on an account so Curve rows reconcile to it. */
    private suspend fun registerCard(
        accountName: String,
        last4: String,
    ) {
        val accounts = repositories.accountRepository.getAllAccounts().first()
        val accountId = accounts.first { it.name == accountName }.id
        repositories.accountAttributeRepository.insert(
            accountId,
            AttributeTypeId(WellKnownIds.ACCOUNT_CARD_LAST4_ATTR_TYPE_ID),
            last4,
        )
    }

    /** Re-imports an already-imported file (plan + execute), threading the funding-card index. */
    private suspend fun reimport(
        importId: CsvImportId,
        strategyName: String = "Curve CSV",
    ) {
        val current = repositories.csvImportRepository.getImport(importId).first()!!
        val strategy =
            repositories.csvImportStrategyRepository
                .getAllStrategies()
                .first()
                .first { it.name == strategyName }
        val currencies = repositories.currencyRepository.getAllCurrencies().first()
        val passThroughAccounts = repositories.passThroughAccountRepository.getAll().first()
        val attributeMatchers =
            AttributeAccountMatcher.registry(repositories.accountAttributeRepository.getAll().first())
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

    private suspend fun transfersBetween(
        sourceName: String,
        targetName: String,
    ): List<Transfer> {
        val accounts = repositories.accountRepository.getAllAccounts().first()
        val sourceId = accounts.firstOrNull { it.name == sourceName }?.id ?: return emptyList()
        val targetId = accounts.firstOrNull { it.name == targetName }?.id ?: return emptyList()
        return repositories.transactionRepository
            .getTransactionsByAccount(sourceId)
            .first()
            .filter { it.sourceAccountId == sourceId && it.targetAccountId == targetId }
    }

    private fun Transfer.isExcluded(): Boolean = attributes.any { it.attributeType.name == "excluded" }

    /** The account's balance in [assetCode] over its counted (non-excluded) transfers. */
    private suspend fun countedBalance(
        accountName: String,
        assetCode: String = "GBP",
    ): BigDecimal {
        val account =
            repositories.accountRepository
                .getAllAccounts()
                .first()
                .firstOrNull { it.name == accountName } ?: return BigDecimal.ZERO
        return repositories.transactionRepository
            .getTransactionsByAccount(account.id)
            .first()
            .filter { !it.isExcluded() && it.amount.asset.code == assetCode }
            .fold(BigDecimal.ZERO) { sum, t ->
                val amount = t.amount.toDisplayValue()
                if (t.targetAccountId == account.id) sum + amount else sum - amount
            }
    }

    private suspend fun assertCurveNetsToZero(assetCode: String = "GBP") {
        assertEquals(0, countedBalance("Curve", assetCode).compareTo(BigDecimal.ZERO), "Curve nets to zero in $assetCode")
    }

    @Test
    fun curveCsvRow_isAChainThroughCurve_supersedingTheCardsGuessedMerchant_andForeignRowStandsAlone() =
        runTest {
            // Underlying card statement: a Curve payment forwarded to the crypto.com card. The
            // pass-through detector expands it into Crypto.com Card -> Curve (funding) and
            // Curve -> Amazon (spend, described "Amazon").
            val card =
                stage(
                    "card_transactions_record_20231120_210200.csv",
                    cardHeaders,
                    listOf(cardRow("2023-11-19 21:15:00", "Crv*Amazon", "-12.99")),
                )
            assertEquals(1, applyAll(listOf(card)).filesImported)
            assertEquals(1, transfersBetween("Curve", "Amazon").size, "card import creates the spend leg")

            // Curve's own export: the same GBP spend under its own merchant name, plus a foreign-currency
            // spend with no card counterpart. The card isn't registered to any account.
            val curve =
                stage(
                    "Transaction History 2023-11-19.csv",
                    curveHeaders,
                    listOf(
                        curveRow("1", "2023-11-19", "AMZN Mktp UK", "GBP", "12.99"),
                        curveRow("2", "2023-11-18", "Mavroni", "EUR", "50.00"),
                    ),
                )
            val result = applyAll(listOf(curve))
            assertEquals(1, result.filesImported)
            assertEquals(0, result.filesSkippedNoStrategy, "the Curve file matches the Curve CSV strategy")
            assertEquals(0, result.filesFailed)
            repositories.maintenanceService.refreshMaterializedViews()

            // The Curve row is the chain "Curve card 7721" -> Curve -> AMZN Mktp UK. Its movement into Curve is
            // the card's funding leg again, so it is excluded and linked to it; its merchant leg names the
            // real merchant and supersedes the card's guess, which is excluded.
            val placeholderLeg = transfersBetween("Curve card 7721", "Curve").single { it.amount.asset.code == "GBP" }
            assertTrue(placeholderLeg.isExcluded(), "the card's funding leg stays the record of the money into Curve")
            val fundingLeg = transfersBetween("Crypto.com Card", "Curve").single()
            assertTrue(!fundingLeg.isExcluded())
            val link =
                repositories.transferRelationshipRepository
                    .getByTransfer(placeholderLeg.id)
                    .first()
                    .filter { it.relationshipType.name == "reconciled" }
            assertTrue(link.any { it.id2 == fundingLeg.id }, "the placeholder leg is linked to the card's funding leg")
            assertTrue(!transfersBetween("Curve", "AMZN Mktp UK").single().isExcluded(), "Curve's merchant leg is counted")
            assertTrue(transfersBetween("Curve", "Amazon").single().isExcluded(), "the card's guessed merchant leg is superseded")
            assertCurveNetsToZero()
            assertEquals(0, countedBalance("Crypto.com Card").compareTo(BigDecimal("-12.99")))

            // The foreign-currency row has no card counterpart, so its placeholder funds it — in EUR, keeping
            // Curve's real FX amount — and Curve still nets to zero.
            val mavroni = transfersBetween("Curve", "Mavroni").single()
            assertEquals("EUR", mavroni.amount.asset.code)
            assertEquals("50", mavroni.amount.toDisplayValue().toString())
            assertTrue(!mavroni.isExcluded(), "the foreign-currency spend is counted")
            assertTrue(!transfersBetween("Curve card 7721", "Curve").single { it.amount.asset.code == "EUR" }.isExcluded())
            assertCurveNetsToZero("EUR")
        }

    @Test
    fun curveCsvRow_reconcilesAgainstFundingLegByCardNumber_evenWhenMerchantDiffers() =
        runTest {
            // The card statement records the Curve charge as "Crv*Sainsburys London"; the pass-through
            // makes a funding leg (Crypto.com Card -> Curve) and a spend leg (Curve -> "Sainsburys London").
            val card =
                stage(
                    "card_transactions_record_20231120_210200.csv",
                    cardHeaders,
                    listOf(cardRow("2023-11-19 21:15:00", "Crv*Sainsburys London", "-22.93")),
                )
            assertEquals(1, applyAll(listOf(card)).filesImported)
            // Register the funding card's last-4 on the Crypto.com Card account (as a user would).
            registerCard("Crypto.com Card", "7721")

            // A second row is funded by an unregistered card (1142) with no counterpart anywhere.
            val curve =
                stage(
                    "Transaction History 2023-11-19.csv",
                    curveHeaders,
                    listOf(
                        curveRow("1", "2023-11-19", "SAINSBURYS", "GBP", "22.93", fundingCard = "7721"),
                        curveRow("2", "2023-11-19", "ALDI", "GBP", "31.25", fundingCard = "1142"),
                    ),
                )
            assertEquals(1, applyAll(listOf(curve)).filesImported)
            repositories.maintenanceService.refreshMaterializedViews()

            // The 7721 row's movement into Curve reconciled against the funding leg identified by the card
            // number; its merchant leg supersedes the card's.
            val placeholderLeg = transfersBetween("Curve card 7721", "Curve").single()
            assertTrue(placeholderLeg.isExcluded(), "the 7721 row's funding is the card's funding leg")
            val fundingLeg = transfersBetween("Crypto.com Card", "Curve").single()
            val link =
                repositories.transferRelationshipRepository
                    .getByTransfer(placeholderLeg.id)
                    .first()
                    .first { it.relationshipType.name == "reconciled" && it.id2 == fundingLeg.id }
            assertEquals(placeholderLeg.id, link.id1)
            assertTrue(!transfersBetween("Curve", "SAINSBURYS").single().isExcluded())
            assertTrue(transfersBetween("Curve", "Sainsburys London").single().isExcluded())

            // The 1142 row has no counterpart: its placeholder funds it, and the spend counts.
            assertTrue(!transfersBetween("Curve card 1142", "Curve").single().isExcluded())
            assertTrue(!transfersBetween("Curve", "ALDI").single().isExcluded(), "unregistered-card row is counted")
            assertEquals(0, countedBalance("Curve card 1142").compareTo(BigDecimal("-31.25")))
            assertEquals(0, countedBalance("Crypto.com Card").compareTo(BigDecimal("-22.93")), "the card account is untouched")
            assertCurveNetsToZero()
        }

    @Test
    fun curveCsvImportedFirst_thenTheCard_reachesTheSameResult() =
        runTest {
            registerCardAccount()
            val curve =
                stage(
                    "Transaction History 2023-11-19.csv",
                    curveHeaders,
                    listOf(
                        curveRow("1", "2023-11-19", "SAINSBURYS", "GBP", "22.93", fundingCard = "7721"),
                        curveRow("2", "2023-11-19", "ALDI", "GBP", "31.25", fundingCard = "1142"),
                    ),
                )
            assertEquals(1, applyAll(listOf(curve)).filesImported)
            // No funding leg exists yet, so even the registered card's row is funded by its placeholder,
            // leaving the card account as its own statement says (only the warm-up row's -3.00).
            assertEquals(0, countedBalance("Crypto.com Card").compareTo(BigDecimal("-3.00")))
            assertCurveNetsToZero()

            val card =
                stage(
                    "card_transactions_record_20231120_210200.csv",
                    cardHeaders,
                    listOf(cardRow("2023-11-19 21:15:00", "Crv*Sainsburys London", "-22.93")),
                )
            assertEquals(1, applyAll(listOf(card)).filesImported)
            repositories.maintenanceService.refreshMaterializedViews()

            // The card's funding leg replaces the placeholder; Curve's merchant leg stays the record.
            assertTrue(transfersBetween("Curve card 7721", "Curve").single().isExcluded())
            assertTrue(!transfersBetween("Crypto.com Card", "Curve").single().isExcluded())
            assertTrue(!transfersBetween("Curve", "SAINSBURYS").single().isExcluded())
            assertTrue(transfersBetween("Curve", "Sainsburys London").single().isExcluded())
            assertTrue(!transfersBetween("Curve card 1142", "Curve").single().isExcluded())
            assertEquals(0, countedBalance("Crypto.com Card").compareTo(BigDecimal("-25.93")))
            assertCurveNetsToZero()
        }

    /** Creates the Crypto.com Card account owning card 7721 before any card statement is imported. */
    private suspend fun registerCardAccount() {
        val warmup =
            stage(
                "card_transactions_record_20230101_000000.csv",
                cardHeaders,
                listOf(cardRow("2023-01-01 10:00:00", "Coffee", "-3.00")),
            )
        applyAll(listOf(warmup))
        registerCard("Crypto.com Card", "7721")
    }

    private val transactionsHeaders =
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

    private fun transactionsRow(
        dateTime: String,
        merchant: String,
        amount: String,
        currency: String = "GBP",
        cardName: String = "Visa Debit",
        last4: String = "7721",
        type: String = "Personal",
        foreign: Pair<String, String>? = null,
        fees: String = "",
    ): List<String> {
        val (date, time) = dateTime.split(" ")
        return listOf(
            "CSV",
            date,
            time,
            merchant,
            amount,
            currency,
            foreign?.first.orEmpty(),
            foreign?.second.orEmpty(),
            cardName,
            last4,
            type,
            "General",
            "",
            fees,
        )
    }

    @Test
    fun curveTransactionsExport_reconcilesByCard_tracksCurveCash_andSkipsOverlap() =
        runTest {
            val card =
                stage(
                    "card_transactions_record_20260510_100000.csv",
                    cardHeaders,
                    listOf(cardRow("2026-05-02 11:05:08", "Crv*Lastpasscom London", "-32.31")),
                )
            assertEquals(1, applyAll(listOf(card)).filesImported)

            val rows =
                listOf(
                    transactionsRow(
                        "2026-05-02 11:05:01",
                        "Lastpass.com",
                        "32.31",
                        foreign = "43.2" to "USD",
                        fees = "Weekend Currency Conversion Fee: £0.48",
                    ),
                    transactionsRow("2026-05-03 09:00:00", "Curve Cash: Deliveroo", "25", "CPT", "Curve Cash", last4 = "", type = ""),
                    transactionsRow("2026-05-04 09:00:00", "Amazon", "208", "CPT", "Curve Cash", last4 = "", foreign = "2.08" to "GBP"),
                )
            val first = stage("Transactions 20260101-20260505.csv", transactionsHeaders, rows)
            // A later export covering the same period repeats every row.
            val overlap = stage("Transactions 20260101-20260601.csv", transactionsHeaders, rows.reversed())
            val result = applyAll(listOf(first, overlap))
            assertEquals(0, result.filesSkippedNoStrategy, "both files match the Curve Transactions strategy")
            assertEquals(0, result.filesFailed)

            // The card is unassigned, so the Card Last-4 tab lists it — but the card's movement into Curve
            // already reconciles the row on its own, and Curve's merchant leg supersedes the card's guess.
            assertEquals(listOf("7721" to 2), unmatchedFundingReferences().map { it.value to it.rowCount })
            val lastpass = transfersBetween("Curve", "Lastpass.com").single()
            assertEquals("32.31", lastpass.amount.toDisplayValue().toString())
            assertEquals("GBP", lastpass.amount.asset.code)
            assertTrue(!lastpass.isExcluded())
            val funding = transfersBetween("Curve card 7721", "Curve").single()
            assertTrue(funding.isExcluded())
            assertTrue(funding.attributes.any { it.attributeType.name == "curve-fee" })
            assertTrue(transfersBetween("Curve", "Lastpasscom London").single().isExcluded())
            assertCurveNetsToZero()

            // Curve Cash is tracked in points: earned from Curve Cashback, then spent on a merchant.
            val earned = transfersBetween("Curve Cashback", "Curve Cash").single()
            assertEquals("CURVECASH", earned.amount.asset.code)
            assertEquals("25", earned.amount.toDisplayValue().toString())
            val spent = transfersBetween("Curve Cash", "Amazon").single()
            assertEquals("208", spent.amount.toDisplayValue().toString())
            // Points never pass through the Curve conduit.
            assertEquals(0, countedBalance("Curve", "CURVECASH").compareTo(BigDecimal.ZERO))
            assertEquals(0, countedBalance("Curve Cash", "CURVECASH").compareTo(BigDecimal("-183")))

            // Assigning the card changes nothing more: the row is already reconciled.
            registerCard("Crypto.com Card", "7721")
            reimport(first.id, strategyName = "Curve CSV (Transactions)")
            repositories.maintenanceService.refreshMaterializedViews()
            assertTrue(unmatchedFundingReferences().isEmpty())
            assertTrue(!transfersBetween("Curve", "Lastpass.com").single().isExcluded())
            assertTrue(transfersBetween("Curve card 7721", "Curve").single().isExcluded())
            assertCurveNetsToZero()
        }

    @Test
    fun assigningUnmatchedCard_addsItToTheAccountSet_andReconcilesTheCurveSpend() =
        runTest {
            val card =
                stage(
                    "card_transactions_record_20231120_210200.csv",
                    cardHeaders,
                    listOf(cardRow("2023-11-19 21:15:00", "Crv*Sainsburys London", "-22.93")),
                )
            assertEquals(1, applyAll(listOf(card)).filesImported)
            // The card account already owns another card, which the assignment must keep.
            registerCard("Crypto.com Card", "9999")

            val curve =
                stage(
                    "Transaction History 2023-11-19.csv",
                    curveHeaders,
                    listOf(
                        curveRow("1", "2023-11-19", "SAINSBURYS", "GBP", "22.93", fundingCard = "7721"),
                        curveRow("2", "2023-11-19", "ALDI", "GBP", "31.25", fundingCard = "1142"),
                        curveRow("3", "2023-11-20", "TESCO", "GBP", "4.10", fundingCard = "1142"),
                    ),
                )
            assertEquals(1, applyAll(listOf(curve)).filesImported)

            val unmatched = unmatchedFundingReferences()
            assertEquals(listOf("7721" to 1, "1142" to 2), unmatched.map { it.value to it.rowCount })
            val reference = unmatched.first { it.value == "7721" }
            assertEquals(listOf(curve.id), reference.imports)
            assertEquals(listOf("Curve CSV"), reference.strategyNames)

            val cardAccountId =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .first { it.name == "Crypto.com Card" }
                    .id
            val typeId = repositories.importEngine.getOrCreateAttributeType(reference.attributeTypeName)
            val existing =
                repositories.accountAttributeRepository
                    .getByAccount(cardAccountId)
                    .first()
                    .single { it.attributeType.id == typeId }
            repositories.importEngine.setAccountAttributeValue(
                accountId = cardAccountId,
                typeId = typeId,
                value = addAttributeToken(existing.value, reference.value),
                existingAttributeId = existing.id,
            )
            val reconciled =
                rerunFundingReconciles(
                    imports = reference.imports,
                    strategies = repositories.csvImportStrategyRepository.getAllStrategies().first(),
                    currencies = repositories.currencyRepository.getAllCurrencies().first(),
                    cryptoAssets = repositories.cryptoRepository.getAllCryptoAssets().first(),
                    passThroughAccounts = repositories.passThroughAccountRepository.getAll().first(),
                    attributeAccountMatchers =
                        AttributeAccountMatcher.registry(repositories.accountAttributeRepository.getAll().first()),
                    accountMappingRepository = repositories.accountMappingRepository,
                    accountRepository = repositories.accountRepository,
                    csvImportRepository = repositories.csvImportRepository,
                    transactionRepository = repositories.transactionRepository,
                    relationshipRepository = repositories.transferRelationshipRepository,
                    transferSourceRepository = repositories.transferSourceRepository,
                    tradeRepository = repositories.tradeRepository,
                    maintenance = maintenance,
                    importEngine = repositories.importEngine,
                )
            // The 7721 row already reconciled against the card's movement into Curve when it was imported.
            assertEquals(0, reconciled)

            val cardAttributes =
                repositories.accountAttributeRepository
                    .getByAccount(cardAccountId)
                    .first()
                    .filter { it.attributeType.id == typeId }
            assertEquals(listOf("9999 7721"), cardAttributes.map { it.value }, "the card joins the account set")
            assertEquals(listOf("1142"), unmatchedFundingReferences().map { it.value })

            repositories.maintenanceService.refreshMaterializedViews()
            assertTrue(transfersBetween("Curve card 7721", "Curve").single().isExcluded(), "the 7721 row is reconciled")
            assertTrue(!transfersBetween("Curve", "SAINSBURYS").single().isExcluded())
            assertEquals(1, transfersBetween("Crypto.com Card", "Curve").size)
            assertCurveNetsToZero()
        }

    private suspend fun unmatchedFundingReferences() =
        findUnmatchedFundingReferences(
            imports = repositories.csvImportRepository.getAllImports().first(),
            strategies = repositories.csvImportStrategyRepository.getAllStrategies().first(),
            csvImportRepository = repositories.csvImportRepository,
            attributeAccountMatchers = AttributeAccountMatcher.registry(repositories.accountAttributeRepository.getAll().first()),
        )

    @Test
    fun reimport_afterCardRegistered_isANoOp() =
        runTest {
            // The card statement is imported, then the Curve file BEFORE any card is registered: the card's
            // movement into Curve reconciles the row anyway. Registering the card and re-importing changes
            // nothing, however often it runs.
            val card =
                stage(
                    "card_transactions_record_20231120_210200.csv",
                    cardHeaders,
                    listOf(cardRow("2023-11-19 21:15:00", "Crv*Sainsburys London", "-22.93")),
                )
            applyAll(listOf(card))
            val curve =
                stage(
                    "Transaction History 2023-11-19.csv",
                    curveHeaders,
                    listOf(curveRow("1", "2023-11-19", "SAINSBURYS", "GBP", "22.93", fundingCard = "7721")),
                )
            applyAll(listOf(curve))
            assertTrue(transfersBetween("Curve card 7721", "Curve").single().isExcluded())

            registerCard("Crypto.com Card", "7721")
            repeat(2) {
                reimport(curve.id)
                repositories.maintenanceService.refreshMaterializedViews()
                assertTrue(transfersBetween("Curve card 7721", "Curve").single().isExcluded())
                assertTrue(!transfersBetween("Curve", "SAINSBURYS").single().isExcluded())
                assertTrue(transfersBetween("Curve", "Sainsburys London").single().isExcluded())
                assertCurveNetsToZero()
            }
        }

    @Test
    fun reimport_rewritesARowImportedAsAPlainSpend_intoAChain() =
        runTest {
            // A database that imported Curve's export before it was a conduit holds plain rows that never
            // pass through Curve. Re-importing with the conduit strategy rewrites them into chains.
            val builtIn =
                repositories.csvImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .first { it.name == "Curve CSV" }
            repositories.importEngine.updateCsvStrategy(builtIn.copy(config = builtIn.config.copy(conduit = null)))
            val curve =
                stage(
                    "Transaction History 2023-11-19.csv",
                    curveHeaders,
                    listOf(curveRow("1", "2023-11-19", "ALDI", "GBP", "31.25", fundingCard = "1142")),
                )
            applyAll(listOf(curve))
            assertTrue(transfersBetween("Curve", "ALDI").isEmpty())
            assertEquals(1, transfersBetween("Curve card 1142", "ALDI").size)

            repositories.importEngine.updateCsvStrategy(builtIn)
            reimport(curve.id)
            repositories.maintenanceService.refreshMaterializedViews()
            assertTrue(!transfersBetween("Curve card 1142", "Curve").single().isExcluded())
            assertTrue(!transfersBetween("Curve", "ALDI").single().isExcluded())
            assertTrue(transfersBetween("Curve card 1142", "ALDI").isEmpty())
            assertCurveNetsToZero()
            reimport(curve.id)
            assertEquals(1, transfersBetween("Curve", "ALDI").size)
        }

    @Test
    fun curveCsvRow_withIdenticalMerchant_countsTheSpendOnce() =
        runTest {
            // Same merchant text on both sides: the card's spend leg and the Curve row's merchant leg are
            // both Curve -> Amazon, £12.99. The spend still counts once and Curve nets to zero.
            val card =
                stage(
                    "card_transactions_record_20231120_210200.csv",
                    cardHeaders,
                    listOf(cardRow("2023-11-19 21:15:00", "Crv*Amazon", "-12.99")),
                )
            applyAll(listOf(card))

            val curve =
                stage(
                    "Transaction History 2023-11-19.csv",
                    curveHeaders,
                    listOf(curveRow("1", "2023-11-19", "Amazon", "GBP", "12.99")),
                )
            applyAll(listOf(curve))
            repositories.maintenanceService.refreshMaterializedViews()
            assertEquals(1, transfersBetween("Curve", "Amazon").count { !it.isExcluded() }, "spend counted once")
            assertEquals(0, countedBalance("Amazon").compareTo(BigDecimal("12.99")))
            assertCurveNetsToZero()
        }

    @Test
    fun reimportingTheCurveFile_producesOnlyDuplicates() =
        runTest {
            val curve =
                stage(
                    "Transaction History 2023-11-19.csv",
                    curveHeaders,
                    listOf(
                        curveRow("1", "2023-11-19", "Amazon", "GBP", "12.99"),
                        curveRow("2", "2023-11-18", "Mavroni", "EUR", "50.00"),
                    ),
                )
            applyAll(listOf(curve))

            suspend fun allTransfers(): List<Transfer> =
                repositories.transactionRepository
                    .getTransactionsByDateRange(
                        startDate = Instant.parse("2023-01-01T00:00:00Z"),
                        endDate = Instant.parse("2024-12-31T00:00:00Z"),
                    ).first()
            val afterFirst = allTransfers().size

            // Curve has no stable per-row id (col 0 is a running index), so re-import relies on
            // all-fields dedupe. Re-staging the same content under a new name must import nothing.
            val curveAgain =
                stage(
                    "Transaction History 2024-01-01.csv",
                    curveHeaders,
                    listOf(
                        curveRow("1", "2023-11-19", "Amazon", "GBP", "12.99"),
                        curveRow("2", "2023-11-18", "Mavroni", "EUR", "50.00"),
                    ),
                )
            val second = applyAll(listOf(curveAgain))
            assertEquals(0, second.filesFailed, "re-import must not fail")
            assertEquals(0, second.transfersCreated, "everything is a duplicate on re-import")
            assertEquals(afterFirst, allTransfers().size)
        }
}

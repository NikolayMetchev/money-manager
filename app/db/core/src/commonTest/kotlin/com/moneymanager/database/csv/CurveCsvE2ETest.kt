@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.csv

import com.moneymanager.csvimporter.AttributeAccountMatcher
import com.moneymanager.csvimporter.BulkImportProgress
import com.moneymanager.csvimporter.CsvBulkResult
import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.csvimporter.executeCsvReimport
import com.moneymanager.csvimporter.planCsvReimport
import com.moneymanager.database.assertBulkProgress
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.importengineapi.createAccountMapping
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
    private suspend fun reimport(importId: CsvImportId) {
        val current = repositories.csvImportRepository.getImport(importId).first()!!
        val strategy =
            repositories.csvImportStrategyRepository
                .getAllStrategies()
                .first()
                .first { it.name == "Curve CSV" }
        val currencies = repositories.currencyRepository.getAllCurrencies().first()
        val passThroughAccounts = repositories.passThroughAccountRepository.getAll().first()
        val fca =
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
                passThroughAccounts = passThroughAccounts,
                attributeAccountMatchers = fca,
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
            attributeAccountMatchers = fca,
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

    @Test
    fun curveCsvSpend_reconcilesWithMappedCardMerchant_andForeignRowStandsAlone() =
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

            // Map Curve's differently-worded merchant onto the same account, so the two spend legs
            // resolve to the same account but keep distinct descriptions — the realistic reconcile case
            // (identical text would instead be a plain fuzzy duplicate).
            val amazonId =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .first { it.name == "Amazon" }
                    .id
            repositories.importEngine.createAccountMapping(Regex("(?i)^Prime Video$"), amazonId)

            // Curve's own export: the same GBP spend under a different name (reconciles) plus a
            // foreign-currency spend (no GBP card counterpart, so it stands alone).
            val curve =
                stage(
                    "Transaction History 2023-11-19.csv",
                    curveHeaders,
                    listOf(
                        curveRow("1", "2023-11-19", "Prime Video", "GBP", "12.99"),
                        curveRow("2", "2023-11-18", "Mavroni", "EUR", "50.00"),
                    ),
                )
            val result = applyAll(listOf(curve))
            assertEquals(1, result.filesImported)
            assertEquals(0, result.filesSkippedNoStrategy, "the Curve file matches the Curve CSV strategy")
            assertEquals(0, result.filesFailed)

            // Both Curve -> Amazon legs are kept, but exactly one is excluded + reconciled-linked.
            repositories.maintenanceService.refreshMaterializedViews()
            val amazonLegs = transfersBetween("Curve", "Amazon")
            assertEquals(2, amazonLegs.size, "both the card-derived and Curve-derived spend legs are kept")
            val excluded =
                amazonLegs.filter { t -> t.attributes.any { it.attributeType.name == "excluded" && it.value == "reconciled" } }
            assertEquals(1, excluded.size, "exactly one Amazon leg is excluded as reconciled")
            val original = amazonLegs.single { it.id != excluded.single().id }
            val reconciledLink =
                repositories.transferRelationshipRepository
                    .getByTransfer(excluded.single().id)
                    .first()
                    .single { it.relationshipType.name == "reconciled" }
            assertEquals(excluded.single().id, reconciledLink.id1)
            assertEquals(original.id, reconciledLink.id2)

            // The foreign-currency row imports as a standalone Curve -> Mavroni EUR spend (no card
            // counterpart to reconcile against), keeping Curve's real FX amount.
            val mavroni = transfersBetween("Curve", "Mavroni").single()
            assertEquals("EUR", mavroni.amount.asset.code)
            assertEquals("50", mavroni.amount.toDisplayValue().toString())
            assertTrue(
                mavroni.attributes.none { it.attributeType.name == "excluded" },
                "the foreign-currency spend is not reconciled/excluded",
            )
        }

    @Test
    fun curveCsvSpend_reconcilesAgainstFundingLegByCardNumber_evenWhenMerchantDiffers() =
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

            // Curve's export names the merchant differently ("SAINSBURYS") — merchant reconcile can't
            // match — but the funding card 7721 identifies the funding leg to reconcile against. A second
            // row is funded by an unregistered card (1142) and must import as a normal new spend.
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

            // The 7721 spend reconciled against the funding leg (excluded + linked), despite the merchant
            // name differing from the card's "Sainsburys London".
            val sainsburys = transfersBetween("Curve", "SAINSBURYS").single()
            assertTrue(
                sainsburys.attributes.any { it.attributeType.name == "excluded" && it.value == "reconciled" },
                "the 7721 Curve spend is reconciled",
            )
            val fundingLeg = transfersBetween("Crypto.com Card", "Curve").single()
            val link =
                repositories.transferRelationshipRepository
                    .getByTransfer(sainsburys.id)
                    .first()
                    .single { it.relationshipType.name == "reconciled" }
            assertEquals(sainsburys.id, link.id1)
            assertEquals(fundingLeg.id, link.id2, "linked to the funding leg identified by the card number")

            // The 1142 spend has no registered card, so it imports as an ordinary (non-excluded) spend.
            val aldi = transfersBetween("Curve", "ALDI").single()
            assertTrue(aldi.attributes.none { it.attributeType.name == "excluded" }, "unregistered-card row is not reconciled")
        }

    @Test
    fun reimport_retroactivelyReconciles_afterCardRegistered() =
        runTest {
            // The realistic sequence: the card statement is imported (creating the funding leg), the Curve
            // file is imported BEFORE any card is registered (so it lands as a plain, unreconciled spend),
            // then the user registers the card and re-imports — which must retroactively reconcile it.
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
            assertTrue(
                transfersBetween("Curve", "SAINSBURYS").single().attributes.none { it.attributeType.name == "excluded" },
                "not reconciled before the card is registered",
            )

            registerCard("Crypto.com Card", "7721")
            reimport(curve.id)
            repositories.maintenanceService.refreshMaterializedViews()

            val sainsburys = transfersBetween("Curve", "SAINSBURYS").single()
            assertTrue(
                sainsburys.attributes.any { it.attributeType.name == "excluded" && it.value == "reconciled" },
                "re-import reconciles the spend once the card is registered",
            )
            val fundingLeg = transfersBetween("Crypto.com Card", "Curve").single()
            val link =
                repositories.transferRelationshipRepository
                    .getByTransfer(sainsburys.id)
                    .first()
                    .single { it.relationshipType.name == "reconciled" }
            assertEquals(fundingLeg.id, link.id2, "linked to the funding leg")

            // Re-running again is a no-op: already reconciled, so the plan doesn't reset it.
            reimport(curve.id)
            assertEquals(1, transfersBetween("Curve", "SAINSBURYS").size)
            assertTrue(transfersBetween("Curve", "SAINSBURYS").single().attributes.any { it.attributeType.name == "excluded" })
        }

    @Test
    fun curveCsvSpend_withIdenticalMerchant_isDroppedAsDuplicate_notDoubleCounted() =
        runTest {
            // Same merchant text on both sides: the card's pass-through spend leg and the Curve row are
            // byte-identical (Curve -> Amazon, "Amazon", £12.99), differing only in the date-only vs
            // datetime stamp. The Curve row must be dropped as a fuzzy duplicate so the spend counts once.
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
            val result = applyAll(listOf(curve))
            assertEquals(0, result.transfersCreated, "the identical Curve spend is a duplicate, not imported")
            assertEquals(1, transfersBetween("Curve", "Amazon").size, "spend counted once")
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

@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.csv

import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.csvimporter.bulkReimportCsv
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.RegexAccountMapping
import com.moneymanager.domain.model.csvstrategy.RegexRule
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.importengineapi.updateCsvStrategy
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * The crypto.com crypto_transactions_record_* export (previously skipped) is now imported by the
 * built-in "Crypto.com Crypto" strategy, which denominates each row in its real Currency/Amount and
 * creates the crypto asset on demand — so a wallet holds a genuine crypto balance (0.37 CRO), not a
 * GBP figure with the crypto amount buried in an attribute.
 */
class CryptoComCryptoE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val now = Clock.System.now()
    private val headers =
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

    private val maintenance =
        object : Maintenance {
            override suspend fun reindex(): Duration = Duration.ZERO

            override suspend fun vacuum(): Duration = Duration.ZERO

            override suspend fun analyze(): Duration = Duration.ZERO

            override suspend fun refreshMaterializedViews(): Duration = Duration.ZERO

            override suspend fun fullRefreshMaterializedViews(): Duration = Duration.ZERO
        }

    @Suppress("LongParameterList")
    private fun row(
        timestamp: String,
        description: String,
        currency: String,
        amount: String,
        nativeAmount: String,
        kind: String,
        toCurrency: String = "",
        toAmount: String = "",
    ): List<String> = listOf(timestamp, description, currency, amount, toCurrency, toAmount, "GBP", nativeAmount, "0.0", kind, "")

    private suspend fun stage(
        fileName: String,
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

    private suspend fun applyAll(imports: List<CsvImport>) =
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
            onProgress = { },
            cryptoRepository = repositories.cryptoRepository,
        )

    private suspend fun reimportAll(imports: List<CsvImport>) =
        bulkReimportCsv(
            imports = imports,
            sourceAccountOverride = null,
            strategies = repositories.csvImportStrategyRepository.getAllStrategies().first(),
            currencies = repositories.currencyRepository.getAllCurrencies().first(),
            accountMappingRepository = repositories.accountMappingRepository,
            accountRepository = repositories.accountRepository,
            csvImportRepository = repositories.csvImportRepository,
            transactionRepository = repositories.transactionRepository,
            relationshipRepository = repositories.transferRelationshipRepository,
            transferSourceRepository = repositories.transferSourceRepository,
            maintenance = maintenance,
            importEngine = repositories.importEngine,
            onProgress = { },
            cryptoRepository = repositories.cryptoRepository,
            tradeRepository = repositories.tradeRepository,
        )

    @Test
    fun cryptoExport_reimportAll_resolvesCryptoAndKeepsBalance() =
        runTest {
            val file =
                stage(
                    "crypto_transactions_record_20231119_121744.csv",
                    listOf(row("2023-11-19 08:00:00", "Card Cashback", "CRO", "0.37", "0.09", "referral_card_cashback")),
                )
            applyAll(listOf(file))
            // Re-import all: the crypto asset must still resolve (no "Currency not found") and the CRO
            // balance must be unchanged.
            reimportAll(repositories.csvImportRepository.getAllImports().first())

            repositories.maintenanceService.refreshMaterializedViews()
            val wallet =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .first { it.name == "Crypto.com" }
            val balance =
                repositories.transactionRepository
                    .getAccountBalances()
                    .first()
                    .first { it.accountId == wallet.id }
                    .balance
            assertEquals("0.37", balance.toDisplayValue().toString())
        }

    @Test
    fun cryptoExport_importsCashbackAsRealCryptoBalance() =
        runTest {
            val crypto =
                stage(
                    "crypto_transactions_record_20231119_121744.csv",
                    listOf(
                        row("2023-11-19 08:00:00", "Card Cashback", "CRO", "0.37", "0.09", "referral_card_cashback"),
                        row("2023-11-20 08:00:00", "Card Cashback", "CRO", "0.42", "0.10", "referral_card_cashback"),
                    ),
                )

            val result = applyAll(listOf(crypto))
            assertEquals(1, result.filesImported, "crypto_* file now imports (no longer skipped)")
            assertEquals(0, result.filesSkippedNoStrategy)

            // CRO asset created on demand.
            val cro = repositories.cryptoRepository.getCryptoAssetByCode("CRO").first()
            assertNotNull(cro, "CRO crypto asset created on demand")
            assertEquals("Cronos", cro.name)

            // The wallet holds a real crypto balance: 0.37 + 0.42 = 0.79 CRO.
            repositories.maintenanceService.refreshMaterializedViews()
            val accounts = repositories.accountRepository.getAllAccounts().first()
            val wallet = accounts.first { it.name == "Crypto.com" }
            val balance =
                repositories.transactionRepository
                    .getAccountBalances()
                    .first()
                    .first { it.accountId == wallet.id }
                    .balance
            assertEquals("CRO", balance.asset.code)
            assertEquals("0.79", balance.toDisplayValue().toString())
        }

    private suspend fun accountByName(name: String) =
        repositories.accountRepository
            .getAllAccounts()
            .first()
            .firstOrNull { it.name == name }

    private suspend fun balanceOf(
        accountName: String,
        assetCode: String,
    ): String {
        repositories.maintenanceService.refreshMaterializedViews()
        val account = assertNotNull(accountByName(accountName))
        return repositories.transactionRepository
            .getAccountBalances()
            .first()
            .first { it.accountId == account.id && it.balance.asset.code == assetCode }
            .balance
            .toDisplayValue()
            .toString()
    }

    @Test
    fun cryptoExchange_importsAsSameAccountTrade() =
        runTest {
            // A crypto→crypto exchange inside the App wallet: one row, TGBP out and CRO in, both legs
            // in the single "Crypto.com" account. Previously this imported as a single-leg transfer
            // into a junk "TGBP -> CRO" account, losing the bought leg.
            val file =
                stage(
                    "crypto_transactions_record_exchange.csv",
                    listOf(
                        row(
                            "2022-03-12 12:17:57",
                            "TGBP -> CRO",
                            "TGBP",
                            "-1499.936259",
                            "1499.94",
                            "crypto_exchange",
                            toCurrency = "CRO",
                            toAmount = "4965.5",
                        ),
                    ),
                )
            applyAll(listOf(file))

            val wallet = assertNotNull(accountByName("Crypto.com"))
            assertNull(accountByName("TGBP -> CRO"), "must not create a junk description-named account")

            val trades = repositories.tradeRepository.getTradesByAccount(wallet.id).first()
            assertEquals(1, trades.size)
            val trade = trades.single()
            assertEquals(wallet.id, trade.fromAccountId)
            assertEquals(wallet.id, trade.toAccountId)
            assertEquals("TGBP", trade.from.asset.code)
            assertEquals("1499.936259", trade.from.toDisplayValue().toString())
            assertEquals("CRO", trade.to.asset.code)
            assertEquals("4965.5", trade.to.toDisplayValue().toString())

            // Both per-asset balances move inside the one account.
            assertEquals("-1499.936259", balanceOf("Crypto.com", "TGBP"))
            assertEquals("4965.5", balanceOf("Crypto.com", "CRO"))

            val staged =
                repositories.csvImportRepository
                    .getImportRows(file.id, limit = 10, offset = 0)
                    .single()
            assertEquals(ImportStatus.IMPORTED, staged.importStatus)
        }

    @Test
    fun vibanRows_dedupeAgainstFiatTrades_eitherImportOrder() =
        runTest {
            // The same viban purchase appears in BOTH exports (identical timestamp/description/amounts;
            // the fiat file's Amount is positive, the crypto file's negative). Both strategies must
            // produce the identical trade so createTrade's exact match books it once — whichever file
            // imports first — and the second file's row surfaces as DUPLICATE.
            val fiatRow =
                row("2022-03-24 22:34:02", "GBP -> TGBP", "GBP", "5000.0", "5000.0", "viban_purchase", "TGBP", "5000.0")
            val cryptoRow =
                row("2022-03-24 22:34:02", "GBP -> TGBP", "GBP", "-5000.0", "5000.0", "viban_purchase", "TGBP", "5000.0")
            val fiat = stage("fiat_transactions_record_20220325_000000.csv", listOf(fiatRow))
            val crypto = stage("crypto_transactions_record_20220325_000000.csv", listOf(cryptoRow))

            // Fiat first, then crypto.
            applyAll(listOf(fiat))
            applyAll(listOf(crypto))

            val wallet = assertNotNull(accountByName("Crypto.com"))
            val cash = assertNotNull(accountByName("Crypto.com Cash"))
            val trades = repositories.tradeRepository.getTradesByAccount(wallet.id).first()
            assertEquals(1, trades.size, "the two files record ONE purchase — exactly one trade")
            assertEquals(cash.id, trades.single().fromAccountId)
            assertEquals(wallet.id, trades.single().toAccountId)

            val cryptoStaged = repositories.csvImportRepository.getImportRows(crypto.id, limit = 10, offset = 0).single()
            assertEquals(ImportStatus.DUPLICATE, cryptoStaged.importStatus, "second source's row must surface as DUPLICATE")
            val fiatStaged = repositories.csvImportRepository.getImportRows(fiat.id, limit = 10, offset = 0).single()
            assertEquals(ImportStatus.IMPORTED, fiatStaged.importStatus)
        }

    @Test
    fun vibanRows_dedupeAgainstFiatTrades_cryptoFileFirst() =
        runTest {
            val fiatRow =
                row("2022-06-16 04:55:30", "TGBP -> GBP", "TGBP", "46.03", "46.03", "crypto_viban", "GBP", "46.03")
            val cryptoRow =
                row("2022-06-16 04:55:30", "TGBP -> GBP", "TGBP", "-46.03", "46.03", "crypto_viban_exchange", "GBP", "46.03")
            val crypto = stage("crypto_transactions_record_20220617_000000.csv", listOf(cryptoRow))
            val fiat = stage("fiat_transactions_record_20220617_000000.csv", listOf(fiatRow))

            // Crypto first, then fiat: the dedupe must be symmetric.
            applyAll(listOf(crypto))
            applyAll(listOf(fiat))

            val wallet = assertNotNull(accountByName("Crypto.com"))
            val cash = assertNotNull(accountByName("Crypto.com Cash"))
            val trades = repositories.tradeRepository.getTradesByAccount(wallet.id).first()
            assertEquals(1, trades.size)
            assertEquals(wallet.id, trades.single().fromAccountId)
            assertEquals(cash.id, trades.single().toAccountId)

            assertEquals(
                ImportStatus.IMPORTED,
                repositories.csvImportRepository
                    .getImportRows(crypto.id, limit = 10, offset = 0)
                    .single()
                    .importStatus,
            )
            assertEquals(
                ImportStatus.DUPLICATE,
                repositories.csvImportRepository
                    .getImportRows(fiat.id, limit = 10, offset = 0)
                    .single()
                    .importStatus,
            )
        }

    @Test
    fun reimport_convertsOldSingleLegTransferToTrade() =
        runTest {
            // Upgrade path: a crypto_exchange row imported under the OLD strategy definition (no
            // TO_CURRENCY/TO_AMOUNT mappings) produced a single-leg transfer into a junk
            // description-named account. Re-importing under the CURRENT definition must delete that
            // transfer, import the row as a trade, and remove the emptied junk account.
            val strategies = repositories.csvImportStrategyRepository.getAllStrategies().first()
            val current = strategies.first { it.name == "Crypto.com Crypto" }
            val old =
                current.copy(
                    fieldMappings =
                        current.fieldMappings
                            .filterKeys { it != TransferField.TO_CURRENCY && it != TransferField.TO_AMOUNT }
                            .mapValues { (field, mapping) ->
                                if (field == TransferField.TARGET_ACCOUNT) {
                                    RegexAccountMapping(
                                        id = mapping.id,
                                        fieldType = TransferField.TARGET_ACCOUNT,
                                        columnName = "Transaction Description",
                                        rules = listOf(RegexRule(pattern = "App wallet", accountName = "Crypto.com Exchange")),
                                    )
                                } else {
                                    mapping
                                }
                            },
                )
            repositories.importEngine.updateCsvStrategy(old)

            val file =
                stage(
                    "crypto_transactions_record_old.csv",
                    listOf(
                        row(
                            "2021-10-15 03:49:59",
                            "BTC -> CRO",
                            "BTC",
                            "-0.00002502",
                            "1.06",
                            "crypto_exchange",
                            toCurrency = "CRO",
                            toAmount = "7.8",
                        ),
                    ),
                )
            applyAll(listOf(file))
            val wallet = assertNotNull(accountByName("Crypto.com"))
            assertNotNull(accountByName("BTC -> CRO"), "old strategy creates the junk counterparty account")
            assertEquals(
                0,
                repositories.tradeRepository
                    .getTradesByAccount(wallet.id)
                    .first()
                    .size,
            )

            // The strategy definition is updated (the catalog-update path) and the file re-imported.
            repositories.importEngine.updateCsvStrategy(current)
            val result = reimportAll(repositories.csvImportRepository.getAllImports().first())
            assertEquals(1, result.tradeConversions, "the junk transfer row must convert to a trade")

            val trades = repositories.tradeRepository.getTradesByAccount(wallet.id).first()
            assertEquals(1, trades.size)
            assertEquals(
                "BTC",
                trades
                    .single()
                    .from.asset.code,
            )
            assertEquals(
                "CRO",
                trades
                    .single()
                    .to.asset.code,
            )
            assertNull(accountByName("BTC -> CRO"), "emptied junk account must be deleted")
            assertEquals(
                ImportStatus.IMPORTED,
                repositories.csvImportRepository
                    .getImportRows(file.id, limit = 10, offset = 0)
                    .single()
                    .importStatus,
            )

            // A second re-import is a no-op: the row now has no transfer id, so the conversion pass
            // must not fire again, and createTrade's idempotency keeps the single trade.
            val again = reimportAll(repositories.csvImportRepository.getAllImports().first())
            assertEquals(0, again.tradeConversions)
            assertEquals(
                1,
                repositories.tradeRepository
                    .getTradesByAccount(wallet.id)
                    .first()
                    .size,
            )
        }

    @Test
    fun dustConversion_manyDebitsOneCredit_linkedThroughConversionsAccount() =
        runTest {
            // A "Convert Dust" event: three tiny tokens converted into one aggregate CRO credit, all at
            // the same timestamp (crypto.com reports the credit as a separate *_credited row with only
            // Currency/Amount — no To Currency). Previously the credit leg errored and the debits imported
            // one-sided; now every leg imports through the shared Conversions account and each debit links
            // to the credit.
            val file =
                stage(
                    "crypto_transactions_record_dust.csv",
                    listOf(
                        row("2022-06-22 22:38:22", "Convert Dust", "CRO", "0.53", "0.06", "dust_conversion_credited"),
                        row("2022-06-22 22:38:22", "Convert Dust", "LUNA2", "-0.016", "0.03", "dust_conversion_debited"),
                        row("2022-06-22 22:38:22", "Convert Dust", "DOT", "-0.0002", "0.0002", "dust_conversion_debited"),
                        row("2022-06-22 22:38:23", "Convert Dust", "ALI", "-1.35", "0.026", "dust_conversion_debited"),
                    ),
                )
            applyAll(listOf(file))

            val accounts = repositories.accountRepository.getAllAccounts().first()
            val wallet = accounts.first { it.name == "Crypto.com" }
            val conversions = accounts.first { it.name == "Crypto.com Conversions" }

            // All four legs imported (the credited leg is no longer an ERROR): the Conversions account
            // touches every leg — 3 debits in + 1 credit out.
            val conversionLegs = repositories.transactionRepository.getTransactionsByAccount(conversions.id).first()
            assertEquals(4, conversionLegs.size)
            val credit = conversionLegs.single { it.sourceAccountId == conversions.id }
            val debits = conversionLegs.filter { it.targetAccountId == conversions.id }
            assertEquals(wallet.id, credit.targetAccountId)
            assertEquals("CRO", credit.amount.asset.code)
            assertEquals(3, debits.size)
            assertEquals(setOf("LUNA2", "DOT", "ALI"), debits.map { it.amount.asset.code }.toSet())

            // Each debit links to the credit with a `conversion` relationship (credit is id2).
            val links = repositories.transferRelationshipRepository.getByTransfer(credit.id).first()
            assertEquals(3, links.size)
            assertEquals(setOf("conversion"), links.map { it.relationshipType.name }.toSet())
            assertEquals(setOf(credit.id), links.map { it.id2 }.toSet())
            assertEquals(debits.map { it.id }.toSet(), links.map { it.id1 }.toSet())

            // The wallet gained the credited CRO; balances stay exact.
            repositories.maintenanceService.refreshMaterializedViews()
            val cro =
                repositories.transactionRepository
                    .getAccountBalances()
                    .first()
                    .first { it.accountId == wallet.id && it.balance.asset.code == "CRO" }
                    .balance
            assertEquals("0.53", cro.toDisplayValue().toString())
        }

    @Test
    fun walletSwap_oneToOne_linkedThroughConversionsAccount() =
        runTest {
            // A 1:1 "Balance Conversion" (crypto_wallet_swap): LUNA -> LUNC.
            val file =
                stage(
                    "crypto_transactions_record_swap.csv",
                    listOf(
                        row("2022-05-13 07:00:00", "Balance Conversion", "LUNC", "0.054", "0.00", "crypto_wallet_swap_credited"),
                        row("2022-05-13 07:00:00", "Balance Conversion", "LUNA", "-0.054", "0.00", "crypto_wallet_swap_debited"),
                    ),
                )
            applyAll(listOf(file))

            val accounts = repositories.accountRepository.getAllAccounts().first()
            val wallet = accounts.first { it.name == "Crypto.com" }
            val conversions = accounts.first { it.name == "Crypto.com Conversions" }

            val legs = repositories.transactionRepository.getTransactionsByAccount(conversions.id).first()
            assertEquals(2, legs.size)
            val credit = legs.single { it.sourceAccountId == conversions.id }
            val debit = legs.single { it.targetAccountId == conversions.id }
            assertEquals(wallet.id, credit.targetAccountId)
            assertEquals(wallet.id, debit.sourceAccountId)

            val links = repositories.transferRelationshipRepository.getByTransfer(credit.id).first()
            assertEquals(1, links.size)
            assertEquals("conversion", links.single().relationshipType.name)
            assertEquals(debit.id, links.single().id1)
            assertEquals(credit.id, links.single().id2)

            // Re-import is idempotent: still exactly two legs and one link (re-import may recreate the
            // transfers, so re-fetch the credit leg rather than reuse the stale id).
            reimportAll(repositories.csvImportRepository.getAllImports().first())
            val legsAfter = repositories.transactionRepository.getTransactionsByAccount(conversions.id).first()
            assertEquals(2, legsAfter.size)
            val creditAfter = legsAfter.single { it.sourceAccountId == conversions.id }
            assertEquals(
                1,
                repositories.transferRelationshipRepository
                    .getByTransfer(creditAfter.id)
                    .first()
                    .size,
            )
        }
}

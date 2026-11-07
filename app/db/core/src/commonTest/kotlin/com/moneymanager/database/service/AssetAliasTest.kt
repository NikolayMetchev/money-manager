package com.moneymanager.database.service

import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.database.json.AssetListExportCodec
import com.moneymanager.database.json.StrategyArtifactCodec
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.AssetAlias
import com.moneymanager.domain.model.AssetExportEntry
import com.moneymanager.domain.model.AssetExportKind
import com.moneymanager.domain.model.AssetListExport
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.repository.assetAliasMap
import com.moneymanager.domain.strategy.StrategyFileNaming
import com.moneymanager.domain.strategy.StrategyKey
import com.moneymanager.domain.strategy.StrategyKind
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportCryptoIntent
import com.moneymanager.importengineapi.LocalCryptoKey
import com.moneymanager.importengineapi.removeAssetAlias
import com.moneymanager.importengineapi.setAssetAlias
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration

/** Asset aliases through the engine, the export, the catalog's crypto list and a real import. */
class AssetAliasTest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val version = AppVersion("test")

    private val maintenance =
        object : Maintenance {
            override suspend fun reindex(): Duration = Duration.ZERO

            override suspend fun vacuum(): Duration = Duration.ZERO

            override suspend fun analyze(): Duration = Duration.ZERO

            override suspend fun refreshMaterializedViews(): Duration = Duration.ZERO

            override suspend fun fullRefreshMaterializedViews(): Duration = Duration.ZERO
        }

    private suspend fun createCrypto(code: String) {
        repositories.importEngine.import(
            ImportBatch(cryptoAssets = listOf(ImportCryptoIntent(LocalCryptoKey(code), Source.Manual, code, name = "$code coin"))),
        )
    }

    private suspend fun aliases() = repositories.cryptoRepository.getAssetAliases().first()

    private suspend fun cryptoIdOf(code: String): CryptoId =
        repositories.cryptoRepository
            .getCryptoAssetByCode(code)
            .first()!!
            .id

    private suspend fun importCelsiusRow(coin: String) {
        val id =
            repositories.csvImportRepository.createImport(
                fileName = "transactions-export-$coin.csv",
                headers =
                    listOf(
                        "Internal id",
                        " Date and time",
                        " Transaction type",
                        " Coin type",
                        " Coin amount",
                        " USD Value",
                        " Original Reward Coin",
                        " Reward Amount In Original Coin",
                        " Confirmed",
                    ),
                rows = listOf(listOf("1", "June 11, 2022 10:38 PM", "Transfer", coin, "4", "1.5", "", "", "Yes")),
                fileChecksum = "checksum-$coin",
                fileLastModified = Clock.System.now(),
            )
        bulkApplyCsv(
            imports = listOf(repositories.csvImportRepository.getImport(id).first()!!),
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
            tradeRepository = repositories.tradeRepository,
        )
    }

    private suspend fun bookedAssetCodes(): Set<String> =
        repositories.accountRepository
            .getAllAccounts()
            .first()
            .flatMap { repositories.transactionRepository.getTransactionsByAccount(it.id).first() }
            .mapTo(mutableSetOf()) { it.amount.asset.code }

    @Test
    fun `an alias is stored upper case and removed`() =
        runDbTest {
            createCrypto("LUNA")
            repositories.importEngine.setAssetAlias(" lunc ", "luna")
            assertEquals(listOf(AssetAlias("LUNC", "LUNA")), aliases())
            assertEquals(mapOf("LUNC" to "LUNA"), repositories.cryptoRepository.assetAliasMap())

            repositories.importEngine.removeAssetAlias("lunc")
            assertEquals(emptyList(), aliases())
        }

    @Test
    fun `an alias needs a known asset and another code`() =
        runDbTest {
            createCrypto("LUNA")
            assertFailsWith<IllegalArgumentException> { repositories.importEngine.setAssetAlias("LUNC", "NOPE") }
            assertFailsWith<IllegalArgumentException> { repositories.importEngine.setAssetAlias("LUNA", "LUNA") }
            assertFailsWith<IllegalArgumentException> { repositories.importEngine.setAssetAlias(" ", "LUNA") }
            assertEquals(emptyList(), aliases())
        }

    @Test
    fun `deleting an asset removes its aliases`() =
        runDbTest {
            createCrypto("LUNA")
            repositories.importEngine.setAssetAlias("LUNC", "LUNA")
            repositories.cryptoRepository.deleteCryptoAsset(cryptoIdOf("LUNA"))
            assertEquals(emptyList(), aliases())
        }

    @Test
    fun `an import books an aliased code as the asset`() =
        runDbTest {
            createCrypto("LUNA")
            createCrypto("LUNC")
            repositories.importEngine.setAssetAlias("LUNC", "LUNA")

            importCelsiusRow("LUNC")

            assertEquals(setOf("LUNA"), bookedAssetCodes())
        }

    @Test
    fun `an import books the catalog alias when the database has no such asset`() =
        runDbTest {
            importCelsiusRow("LUNC")
            assertEquals(setOf("LUNA"), bookedAssetCodes())
        }

    @Test
    fun `the export round-trips into another database state and only adds`() =
        runDbTest {
            createCrypto("LUNA")
            repositories.importEngine.setAssetAlias("LUNC", "LUNA")
            val json = AssetListExportCodec.encode(repositories.assetExportService.toExport(version))

            repositories.importEngine.removeAssetAlias("LUNC")
            repositories.importEngine.setAssetAlias("OLD", "LUNA")
            val incoming = AssetListExportCodec.decode(json)
            repositories.assetExportService.importAssets(
                incoming.copy(assets = incoming.assets + AssetExportEntry("NEWC", "New coin", AssetExportKind.CRYPTO)),
            )

            assertEquals(listOf("LUNC", "OLD"), aliases().map { it.alias })
            assertEquals(
                "New coin",
                repositories.cryptoRepository
                    .getCryptoAssetByCode("NEWC")
                    .first()
                    ?.name,
            )
        }

    @Test
    fun `the export sorts assets and aliases`() =
        runDbTest {
            createCrypto("ZZZ")
            createCrypto("AAA")
            repositories.importEngine.setAssetAlias("YY", "AAA")
            repositories.importEngine.setAssetAlias("BB", "AAA")

            val decoded = AssetListExportCodec.decode(AssetListExportCodec.encode(repositories.assetExportService.toExport(version)))

            assertEquals(listOf("AAA", "ZZZ"), decoded.assets.map { it.code })
            assertEquals(
                listOf("BB", "YY"),
                decoded.assets
                    .first()
                    .aliases
                    .toList(),
            )
        }

    private val cryptoList =
        AssetListExport(
            assets =
                listOf(
                    AssetExportEntry("LUNA", "Terra", AssetExportKind.CRYPTO, aliases = setOf("LUNC")),
                    AssetExportEntry("BTC", "Bitcoin", AssetExportKind.CRYPTO, aliases = setOf("XXBT")),
                    AssetExportEntry("USD", "US Dollar", AssetExportKind.CURRENCY, 2, aliases = setOf("ZUSD")),
                ),
        )

    private suspend fun localCryptoListHash(): String? =
        repositories
            .strategyLibrary
            .listLocal(version)
            .firstOrNull { it.key == StrategyKey(StrategyKind.CRYPTO_ASSETS, StrategyFileNaming.CRYPTO_ASSETS_NAME) }
            ?.contentHash

    private fun catalogHash(export: AssetListExport): String =
        StrategyArtifactCodec.canonicalHash(StrategyKind.CRYPTO_ASSETS, AssetListExportCodec.encode(export))

    @Test
    fun `the installed crypto list keeps the catalog hash whatever else the database gains`() =
        runDbTest {
            assertEquals(null, localCryptoListHash())
            repositories.strategyLibrary.applyIncoming(
                StrategyKey(StrategyKind.CRYPTO_ASSETS, StrategyFileNaming.CRYPTO_ASSETS_NAME),
                AssetListExportCodec.encode(cryptoList),
                emptyMap(),
            )
            assertEquals(catalogHash(cryptoList), localCryptoListHash())

            createCrypto("OTHER")
            repositories.importEngine.setAssetAlias("MINE", "LUNA")
            assertEquals(catalogHash(cryptoList), localCryptoListHash())

            val own = repositories.assetExportService.toExport(version).assets
            assertEquals(listOf("LUNA", "OTHER"), own.map { it.code })
            assertEquals(setOf("MINE"), own.first().aliases)
            assertTrue(aliases().single { it.alias == "LUNC" }.published)
        }

    @Test
    fun `installing the list again adds nothing`() =
        runDbTest {
            val key = StrategyKey(StrategyKind.CRYPTO_ASSETS, StrategyFileNaming.CRYPTO_ASSETS_NAME)
            val json = AssetListExportCodec.encode(cryptoList)
            repositories.strategyLibrary.applyIncoming(key, json, emptyMap())
            val before = aliases()
            repositories.strategyLibrary.applyIncoming(key, json, emptyMap())
            assertEquals(before, aliases())
            assertEquals(catalogHash(cryptoList), localCryptoListHash())
        }
}

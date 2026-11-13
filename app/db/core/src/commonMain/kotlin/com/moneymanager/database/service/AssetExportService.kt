package com.moneymanager.database.service

import com.moneymanager.domain.model.AliasPublisher
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.AssetAlias
import com.moneymanager.domain.model.AssetExportEntry
import com.moneymanager.domain.model.AssetExportKind
import com.moneymanager.domain.model.AssetListExport
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.importengineapi.AssetAliasMutation
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportCryptoIntent
import com.moneymanager.importengineapi.ImportCurrencyIntent
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.LocalCryptoKey
import com.moneymanager.importengineapi.LocalCurrencyKey
import kotlinx.coroutines.flow.first

/** Converts a database's assets and their aliases to the portable [AssetListExport] and back. */
class AssetExportService(
    private val currencyRepository: CurrencyReadRepository,
    private val cryptoRepository: CryptoReadRepository,
    private val importEngine: ImportEngine,
) {
    /**
     * A database's own assets: every asset and alias the published lists can't bring back. An asset a list installed
     * comes along only to carry an alias a person added to it.
     */
    suspend fun toExport(appVersion: AppVersion): AssetListExport {
        val aliases =
            cryptoRepository
                .getAssetAliases()
                .first()
                .filter { it.publisher == AliasPublisher.PERSON }
                .byAsset()
        val listedCurrencies = currencyRepository.getSystemCreatedCurrencies().first().mapTo(mutableSetOf()) { it.code }
        val listedCrypto = cryptoRepository.getSystemCreatedCryptoAssets().first().mapTo(mutableSetOf()) { it.code }
        val entries =
            assetEntries(aliases) { asset ->
                asset.code !in (if (asset.kind == AssetExportKind.CURRENCY) listedCurrencies else listedCrypto) ||
                    asset.aliases.isNotEmpty()
            }
        return AssetListExport(version = appVersion.value, assets = entries)
    }

    /**
     * The local copy of the catalog's crypto list: what installing it left, whatever else the database has since
     * gained. Only an alias it set makes a currency part of it.
     */
    suspend fun toPublishedCryptoList(appVersion: AppVersion): AssetListExport {
        val aliases =
            cryptoRepository
                .getAssetAliases()
                .first()
                .filter { it.publisher == AliasPublisher.CRYPTO_LIST }
                .byAsset()
        val listedCrypto = cryptoRepository.getSystemCreatedCryptoAssets().first().mapTo(mutableSetOf()) { it.code }
        val entries =
            assetEntries(aliases) { asset ->
                if (asset.kind == AssetExportKind.CURRENCY) asset.aliases.isNotEmpty() else asset.code in listedCrypto
            }
        return AssetListExport(version = appVersion.value, assets = entries)
    }

    private fun List<AssetAlias>.byAsset(): Map<String, Set<String>> =
        groupBy({ it.assetCode }, { it.alias }).mapValues { it.value.toSet() }

    private suspend fun assetEntries(
        aliases: Map<String, Set<String>>,
        keep: (AssetExportEntry) -> Boolean,
    ): List<AssetExportEntry> {
        val currencies = currencyRepository.getAllCurrencies().first()
        val currencyCodes = currencies.mapTo(mutableSetOf()) { it.code.uppercase() }
        val currencyEntries =
            currencies.map {
                AssetExportEntry(it.code, it.name, AssetExportKind.CURRENCY, it.fractionDigits, aliases[it.code.uppercase()].orEmpty())
            }
        val cryptoEntries =
            cryptoRepository.getAllCryptoAssets().first().map {
                // A code a currency shares is read as the currency, so its aliases are the currency's.
                val own = if (it.code.uppercase() in currencyCodes) emptySet() else aliases[it.code.uppercase()].orEmpty()
                AssetExportEntry(it.code, it.name, AssetExportKind.CRYPTO, aliases = own)
            }
        return (currencyEntries + cryptoEntries).filter(keep)
    }

    /**
     * Union semantics, like the global account mappings: adds the assets the database lacks and sets every listed
     * alias. Existing assets are never renamed or removed (transfers reference them), and an alias the file doesn't
     * list stays.
     */
    suspend fun importAssets(export: AssetListExport) = apply(export, Source.Manual, AliasPublisher.PERSON)

    /** Applies the catalog's crypto list: [importAssets], with what it adds marked as the list's. */
    suspend fun installPublishedCryptoList(export: AssetListExport) = apply(export, Source.System, AliasPublisher.CRYPTO_LIST)

    private suspend fun apply(
        export: AssetListExport,
        source: Source,
        publisher: AliasPublisher,
    ) {
        val currencyCodes = currencyRepository.getAllCurrencies().first().mapTo(mutableSetOf()) { it.code }
        val cryptoCodes = cryptoRepository.getAllCryptoAssets().first().mapTo(mutableSetOf()) { it.code }
        val (currencies, cryptos) = export.assets.partition { it.kind == AssetExportKind.CURRENCY }
        // A published list never takes over an alias someone else set (a person's own mapping in particular).
        val othersAliases =
            if (publisher == AliasPublisher.PERSON) {
                emptySet()
            } else {
                cryptoRepository
                    .getAssetAliases()
                    .first()
                    .filter { it.publisher != publisher }
                    .mapTo(mutableSetOf()) { it.alias }
            }
        importEngine.import(
            ImportBatch(
                currencies =
                    currencies.filter { it.code !in currencyCodes }.map {
                        ImportCurrencyIntent(
                            key = LocalCurrencyKey(it.code),
                            source = source,
                            code = it.code,
                            name = it.name,
                            fractionDigits = it.fractionDigits,
                        )
                    },
                cryptoAssets =
                    cryptos.filter { it.code !in cryptoCodes }.map {
                        ImportCryptoIntent(key = LocalCryptoKey(it.code), source = source, code = it.code, name = it.name)
                    },
                assetAliasMutations =
                    export.assets.flatMap { asset ->
                        asset.aliases.filter { it !in othersAliases }.map { AssetAliasMutation.Set(it, asset.code, publisher) }
                    },
            ),
        )
    }
}

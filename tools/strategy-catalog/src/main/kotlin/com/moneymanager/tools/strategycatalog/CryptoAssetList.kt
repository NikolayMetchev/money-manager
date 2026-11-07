package com.moneymanager.tools.strategycatalog

import com.moneymanager.cryptodata.CryptoDatasetEntry
import com.moneymanager.domain.model.AssetExportEntry
import com.moneymanager.domain.model.AssetExportKind
import com.moneymanager.domain.model.AssetListExport
import com.moneymanager.domain.model.CurrencyExportEntry

/**
 * The catalog's crypto list: the dataset's coins, each with its aliases. A row that is an alias isn't an asset, and an
 * alias can name a currency (Kraken's `ZUSD`), so that currency comes along to carry it. A coin sharing an ISO code is
 * left out, as an import reads that code as the currency.
 */
internal fun cryptoAssetListExport(
    rows: List<CryptoDatasetEntry>,
    currencies: List<CurrencyExportEntry>,
): AssetListExport {
    val aliases = rows.filter { it.aliasOf != null }.groupBy({ requireNotNull(it.aliasOf) }, { it.symbol })
    val coins = rows.filter { it.aliasOf == null }
    val currencyByCode = currencies.associateBy { it.code }
    val coinCodes = coins.mapTo(mutableSetOf()) { it.symbol }
    aliases.keys.forEach { target ->
        require(target in coinCodes || target in currencyByCode) { "The alias target $target is neither a coin nor a currency" }
    }
    val coinEntries =
        coins.filter { it.symbol !in currencyByCode }.map {
            AssetExportEntry(it.symbol, it.name, AssetExportKind.CRYPTO, aliases = aliases[it.symbol].orEmpty().toSet())
        }
    val currencyEntries =
        aliases.keys.mapNotNull { currencyByCode[it] }.map {
            AssetExportEntry(it.code, it.name, AssetExportKind.CURRENCY, it.fractionDigits, aliases.getValue(it.code).toSet())
        }
    return AssetListExport(assets = coinEntries + currencyEntries)
}

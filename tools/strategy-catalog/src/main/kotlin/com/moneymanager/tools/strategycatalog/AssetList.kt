package com.moneymanager.tools.strategycatalog

import com.moneymanager.cryptodata.CryptoDatasetEntry
import com.moneymanager.domain.model.AssetExportEntry
import com.moneymanager.domain.model.AssetExportKind
import com.moneymanager.domain.model.AssetListExport
import com.moneymanager.domain.model.CurrencyExportEntry

/**
 * The catalog's asset list: every ISO 4217 currency and the crypto dataset's coins, each with its aliases. A row that is
 * an alias isn't an asset, and an alias can name a currency (Kraken's `ZUSD`), joining that currency's own aliases. A
 * coin sharing an ISO code is left out, as an import reads that code as the currency.
 */
internal fun assetListExport(
    rows: List<CryptoDatasetEntry>,
    currencies: List<CurrencyExportEntry>,
): AssetListExport {
    val aliases = rows.filter { it.aliasOf != null }.groupBy({ requireNotNull(it.aliasOf) }, { it.symbol })
    val coins = rows.filter { it.aliasOf == null }
    val currencyCodes = currencies.mapTo(mutableSetOf()) { it.code }
    val coinCodes = coins.mapTo(mutableSetOf()) { it.symbol }
    aliases.keys.forEach { target ->
        require(target in coinCodes || target in currencyCodes) { "The alias target $target is neither a coin nor a currency" }
    }
    val currencyEntries =
        currencies.map {
            AssetExportEntry(it.code, it.name, AssetExportKind.CURRENCY, it.fractionDigits, it.aliases + aliases[it.code].orEmpty())
        }
    val coinEntries =
        coins.filter { it.symbol !in currencyCodes }.map {
            AssetExportEntry(it.symbol, it.name, AssetExportKind.CRYPTO, aliases = aliases[it.symbol].orEmpty().toSet())
        }
    return AssetListExport(assets = currencyEntries + coinEntries)
}

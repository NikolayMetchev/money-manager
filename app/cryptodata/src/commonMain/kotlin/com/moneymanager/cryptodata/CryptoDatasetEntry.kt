package com.moneymanager.cryptodata

/**
 * One row of the crypto catalog dataset: a ticker [symbol], a human-readable [name], and an optional
 * [decimals] precision. CoinGecko's coin list carries no decimals, so [decimals] is normally null and
 * the importer derives an asset's precision from the observed data instead.
 */
data class CryptoDatasetEntry(
    val symbol: String,
    val name: String,
    val decimals: Int? = null,
)

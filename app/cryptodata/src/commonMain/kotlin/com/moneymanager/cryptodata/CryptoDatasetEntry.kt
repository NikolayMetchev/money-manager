package com.moneymanager.cryptodata

/**
 * One row of the crypto catalog dataset: a ticker [symbol], a human-readable [name], and [aliasOf], the code of the
 * asset this ticker is another code for (LUNC is LUNA), which needn't have a row of its own: it can be a currency.
 * Precision is not part of the catalog — every crypto asset uses the fixed 18-decimal scale factor.
 */
data class CryptoDatasetEntry(
    val symbol: String,
    val name: String,
    val aliasOf: String? = null,
)

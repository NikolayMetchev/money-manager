package com.moneymanager.cryptodata

/**
 * One row of the crypto catalog dataset: a ticker [symbol] and a human-readable [name]. Precision is
 * not part of the catalog — every crypto asset uses the fixed 18-decimal scale factor.
 */
data class CryptoDatasetEntry(
    val symbol: String,
    val name: String,
)

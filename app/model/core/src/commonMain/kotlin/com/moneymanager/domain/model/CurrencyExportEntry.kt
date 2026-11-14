package com.moneymanager.domain.model

/**
 * One ISO 4217 currency as the catalog renders it into its published asset list (an [AssetExportEntry] of kind
 * [AssetExportKind.CURRENCY]), and as a test database is seeded with.
 */
data class CurrencyExportEntry(
    val code: String,
    val name: String,
    /** The ISO minor-unit width (2 for GBP, 0 for JPY), which native and the browser pad amounts to; null for none. */
    val fractionDigits: Int? = null,
    /** Other names a statement writes the currency as (`POUND STERLING`), installed as its aliases; see `resolveAssetCode`. */
    val aliases: Set<String> = emptySet(),
)

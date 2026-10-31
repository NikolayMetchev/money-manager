package com.moneymanager.domain.model

import com.moneymanager.domain.model.serialization.SortedByListSerializer
import kotlinx.serialization.Serializable

/**
 * Portable JSON artifact for the ISO 4217 currency list, published in the strategy catalog next to the built-in
 * strategies. The app bundles no currencies: a new database installs them from here. Applying it only ever adds
 * currencies (see `StrategyLibraryService`), so [currencies] is a set in spirit and serializes in code order.
 */
@Serializable
data class CurrencyListExport(
    /** App version the artifact was exported under; blanked when hashing for semantic comparison. */
    val version: String = "",
    val name: String,
    @Serializable(with = SortedCurrencyExportEntryListSerializer::class)
    val currencies: List<CurrencyExportEntry> = emptyList(),
)

@Serializable
data class CurrencyExportEntry(
    val code: String,
    val name: String,
    /** The ISO minor-unit width (2 for GBP, 0 for JPY), which native and the browser pad amounts to; null for none. */
    val fractionDigits: Int? = null,
)

object SortedCurrencyExportEntryListSerializer : SortedByListSerializer<CurrencyExportEntry>(
    CurrencyExportEntry.serializer(),
    compareBy({ it.code }, { it.name }),
)

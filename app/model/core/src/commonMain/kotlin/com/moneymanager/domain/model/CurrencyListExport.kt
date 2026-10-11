package com.moneymanager.domain.model

import com.moneymanager.domain.model.serialization.SortedByListSerializer
import kotlinx.serialization.Serializable

/**
 * Portable JSON artifact for the reference list of currencies a database is seeded with, published in
 * the strategy catalog next to the built-in strategies so an installed app picks up codes added after it
 * shipped. Applying it only ever adds currencies (see `StrategyLibraryService`), so [currencies] is a
 * set in spirit and serializes in code order.
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
)

object SortedCurrencyExportEntryListSerializer : SortedByListSerializer<CurrencyExportEntry>(
    CurrencyExportEntry.serializer(),
    compareBy({ it.code }, { it.name }),
)

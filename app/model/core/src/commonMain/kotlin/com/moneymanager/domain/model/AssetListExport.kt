package com.moneymanager.domain.model

import com.moneymanager.domain.model.serialization.SortedByListSerializer
import com.moneymanager.domain.model.serialization.SortedStringSetSerializer
import kotlinx.serialization.Serializable

/**
 * A database's own assets as a portable JSON artifact, exported from the Assets screen and synced with the strategy
 * library: every crypto asset, and the currencies the published list can't bring back (a person's own, or one holding
 * an alias). Applying it only ever adds (see `AssetExportService`), so [assets] is a set in spirit and serializes in
 * code order.
 */
@Serializable
data class AssetListExport(
    /** App version the artifact was exported under; blanked when hashing for semantic comparison. */
    val version: String = "",
    @Serializable(with = SortedAssetExportEntryListSerializer::class)
    val assets: List<AssetExportEntry> = emptyList(),
)

@Serializable
data class AssetExportEntry(
    val code: String,
    val name: String,
    val kind: AssetExportKind,
    /** A currency's ISO minor-unit width; null for none, and for crypto. */
    val fractionDigits: Int? = null,
    @Serializable(with = SortedStringSetSerializer::class)
    val aliases: Set<String> = emptySet(),
)

@Serializable
enum class AssetExportKind { CURRENCY, CRYPTO }

object SortedAssetExportEntryListSerializer : SortedByListSerializer<AssetExportEntry>(
    AssetExportEntry.serializer(),
    compareBy({ it.code }, { it.kind }),
)

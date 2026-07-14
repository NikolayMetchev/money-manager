package com.moneymanager.database.json

import com.moneymanager.domain.strategy.StrategyFileNaming
import com.moneymanager.domain.strategy.StrategyKey
import com.moneymanager.domain.strategy.StrategyKind

/**
 * Kind-dispatching helpers over the per-kind export codecs, shared by the strategy library (sync,
 * catalog install) and the catalog site generator so both sides compute identical hashes.
 */
object StrategyArtifactCodec {
    /**
     * A stable, version-independent content hash of an artifact: the JSON is re-encoded with the
     * `version` stamp blanked so semantically-equal artifacts exported under different app versions
     * hash identically, then hashed with FNV-1a 64-bit (platform-independent). The re-encode also
     * canonicalizes collection order — the export models serialize their order-insensitive
     * collections through sorted serializers (see `SortedListSerializer` and friends in app/model) —
     * so artifacts differing only in collection order hash identically too.
     */
    fun canonicalHash(
        kind: StrategyKind,
        json: String,
    ): String {
        val canonical =
            when (kind) {
                StrategyKind.CSV, StrategyKind.QIF ->
                    CsvStrategyExportCodec.encode(CsvStrategyExportCodec.decode(json).copy(version = ""))
                StrategyKind.API ->
                    ApiStrategyExportCodec.encode(ApiStrategyExportCodec.decode(json).copy(version = ""))
                StrategyKind.GLOBAL_MAPPINGS ->
                    AccountMappingExportCodec.encode(AccountMappingExportCodec.decode(json).copy(version = ""))
                StrategyKind.PASS_THROUGH ->
                    PassThroughExportCodec.encode(PassThroughExportCodec.decode(json).copy(version = ""))
            }
        return contentHash(canonical)
    }

    /**
     * Decodes [json] with the codec for [kind] and returns the artifact's embedded name (throws on
     * malformed JSON). Used to validate catalog submissions: the name must match the filename stem.
     */
    fun embeddedName(
        kind: StrategyKind,
        json: String,
    ): String =
        when (kind) {
            StrategyKind.CSV, StrategyKind.QIF -> CsvStrategyExportCodec.decode(json).name
            StrategyKind.API -> ApiStrategyExportCodec.decode(json).name
            StrategyKind.GLOBAL_MAPPINGS -> StrategyFileNaming.GLOBAL_MAPPINGS_NAME
            StrategyKind.PASS_THROUGH -> PassThroughExportCodec.decode(json).name
        }

    fun canonicalHash(
        key: StrategyKey,
        json: String,
    ): String = canonicalHash(key.kind, json)

    private fun contentHash(text: String): String {
        var hash = FNV_OFFSET_BASIS
        for (byte in text.encodeToByteArray()) {
            hash = hash xor (byte.toLong() and 0xff)
            hash *= FNV_PRIME
        }
        return hash.toULong().toString(HEX_RADIX)
    }

    private const val FNV_OFFSET_BASIS: Long = -3750763034362895579L // 0xcbf29ce484222325
    private const val FNV_PRIME: Long = 1099511628211L
    private const val HEX_RADIX: Int = 16
}

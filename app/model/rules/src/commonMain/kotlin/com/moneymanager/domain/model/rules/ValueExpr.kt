package com.moneymanager.domain.model.rules

import com.moneymanager.domain.model.serialization.SortedStringSetSerializer
import com.moneymanager.domain.model.serialization.SortedStringToStringMapSerializer
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * A reusable regex extraction: a [pattern] matched (case-insensitively) against a value, and an
 * [outputTemplate] producing the result from the match. Templates support `$0` (the whole match),
 * `$1`..`$9` (numbered groups) and `${name}` (named groups). When the pattern does not match the caller
 * decides the fallback.
 */
@Serializable
data class Extraction(
    val pattern: String,
    val outputTemplate: String = "$0",
)

/**
 * How a value is read from a [Record]: the first of [paths] holding a non-blank value, cleaned through
 * [extraction] when one is set (the raw value is kept when the pattern doesn't match, so nothing is
 * lost). Replaces the per-field `columnName` + `fallbackColumns` + extraction triples.
 *
 * @property paths Tried in order until one yields a non-blank value — order is semantic.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ValueExpr(
    val paths: List<String>,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val extraction: Extraction? = null,
) {
    init {
        require(paths.isNotEmpty()) { "ValueExpr needs at least one path" }
    }

    /** The path read first; the one the value comes from whenever it isn't blank. */
    val primaryPath: String get() = paths.first()

    companion object {
        /** A value read from [path] alone, falling back to [fallbacks] in order when it is blank. */
        fun of(
            path: String,
            vararg fallbacks: String,
            extraction: Extraction? = null,
        ): ValueExpr = ValueExpr(listOf(path) + fallbacks, extraction)
    }
}

/**
 * How a source's asset codes are turned into the codes Money Manager uses: [stripSuffixes] are removed
 * first (Kraken's Earn holdings `XETH.F` → `XETH`), then [aliases] map what's left (Kraken `XXBT` →
 * `BTC`, Koinly `KNCL` → `KNC`). Codes are upper-cased and trimmed before both steps; keys are upper case.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AssetCodeRules(
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @Serializable(with = SortedStringToStringMapSerializer::class)
    val aliases: Map<String, String> = emptyMap(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @Serializable(with = SortedStringSetSerializer::class)
    val stripSuffixes: Set<String> = emptySet(),
) {
    @Transient
    private val aliasesByUpperCode: Map<String, String> =
        aliases.entries.associate { (code, alias) ->
            code.trim().uppercase() to
                alias.trim().uppercase()
        }

    val isEmpty: Boolean get() = aliases.isEmpty() && stripSuffixes.isEmpty()

    /** [code] normalised: trimmed, upper-cased, a known suffix stripped, then aliased. */
    fun canonical(code: String): String {
        val normalised = code.trim().uppercase()
        val stripped =
            stripSuffixes
                .firstOrNull { normalised.endsWith(it.trim().uppercase()) }
                ?.let { normalised.dropLast(it.trim().length) }
                ?: normalised
        return aliasesByUpperCode[stripped] ?: stripped
    }
}

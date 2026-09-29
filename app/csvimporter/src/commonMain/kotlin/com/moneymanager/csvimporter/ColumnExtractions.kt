package com.moneymanager.csvimporter

import com.moneymanager.domain.model.csvstrategy.ColumnExtraction
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig

/**
 * Runs [extraction]'s regex (case-insensitively) against [value] and substitutes its
 * [ColumnExtraction.outputTemplate]; [value] unchanged when the pattern doesn't match. For one-off use;
 * [CsvTransferMapper] caches compiled patterns across rows.
 */
internal fun extractOrRaw(
    value: String,
    extraction: ColumnExtraction?,
): String {
    if (extraction == null) return value
    val match = Regex(extraction.pattern, RegexOption.IGNORE_CASE).find(value) ?: return value
    return substituteExtractionTemplate(extraction.outputTemplate, match)
}

/**
 * Substitutes capture-group references in [template] from [match]. Supports `$0` (whole match),
 * `$1`..`$9` (numbered groups) and `${name}` (named groups). Unknown/absent groups become "".
 */
internal fun substituteExtractionTemplate(
    template: String,
    match: MatchResult,
): String {
    val out = StringBuilder()
    var i = 0
    while (i < template.length) {
        val token = parseTemplateToken(template, i, match)
        if (token == null) {
            out.append(template[i])
            i++
        } else {
            out.append(token.first)
            i += token.second
        }
    }
    return out.toString()
}

/**
 * Parses a `$`-token at [start] in [template], returning (substituted value, characters consumed)
 * for `${name}` and `$0`..`$9`, or null when there is no token to substitute at this position.
 */
private fun parseTemplateToken(
    template: String,
    start: Int,
    match: MatchResult,
): Pair<String, Int>? {
    if (template[start] != '$' || start + 1 >= template.length) return null
    val next = template[start + 1]
    if (next == '{') {
        val end = template.indexOf('}', startIndex = start + 2)
        if (end == -1) return null
        val value = (match.groups as? MatchNamedGroupCollection)?.get(template.substring(start + 2, end))?.value.orEmpty()
        return value to (end - start + 1)
    }
    if (next.isDigit()) return match.groupValues.getOrNull(next - '0').orEmpty() to 2
    return null
}

/** [code] normalised (trimmed, upper case) and mapped through the strategy's `assetAliases`. */
internal fun CsvStrategyConfig<*>.resolveAssetCode(code: String): String {
    val normalised = code.trim().uppercase()
    return assetAliases[normalised]?.trim()?.uppercase() ?: normalised
}

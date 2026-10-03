package com.moneymanager.domain.model.rules

/**
 * One row or item a strategy's rules are evaluated against. A CSV row exposes its columns by name; an
 * API item exposes its JSON by dot-path. Structural lookups ([arrayTexts], [objectSize]) are only
 * meaningful for JSON, so a flat record returns null for them.
 */
interface Record {
    /** Whether [path] is present at all (a column the file has; a JSON field that isn't `null`). */
    fun exists(path: String): Boolean

    /** The value at [path] as text, or null when absent (or not a scalar). */
    fun text(path: String): String?

    /** The scalar elements of the array at [path], or null when it isn't an array. */
    fun arrayTexts(path: String): List<String>? = null

    /** The number of entries of the object at [path], or null when it isn't an object. */
    fun objectSize(path: String): Int? = null
}

/** A flat record over named columns, e.g. a CSV row; a column the file doesn't have is absent. */
class ColumnRecord(
    private val values: List<String>,
    private val indexByName: Map<String, Int>,
) : Record {
    override fun exists(path: String): Boolean = path in indexByName

    // A cell missing from a short row reads as absent (null), like a column the file doesn't have.
    override fun text(path: String): String? = indexByName[path]?.let { values.getOrNull(it) }
}

/**
 * Evaluates [Condition]s and [ValueExpr]s. Holds a cache of compiled patterns, so create one per import
 * (rules run against every row, and compiling in place would dominate the cost). Not thread-safe.
 */
class RuleEvaluator {
    private val patternCache = HashMap<String, Regex>()

    /** [pattern] compiled case-insensitively, the convention for every pattern a strategy declares. */
    fun regex(pattern: String): Regex = patternCache.getOrPut(pattern) { Regex(pattern, RegexOption.IGNORE_CASE) }

    /** Whether every one of [conditions] holds for [record] (true for an empty list). */
    fun all(
        conditions: List<Condition>,
        record: Record,
    ): Boolean = conditions.all { matches(it, record) }

    /** The first of [conditions] that holds for [record], or null. */
    fun firstMatching(
        conditions: List<Condition>,
        record: Record,
    ): Condition? = conditions.firstOrNull { matches(it, record) }

    fun matches(
        condition: Condition,
        record: Record,
    ): Boolean {
        val raw = record.text(condition.path)
        val trimmed = raw?.trim().orEmpty()
        val operand = condition.value.orEmpty()
        return when (condition.op) {
            ConditionOp.EXISTS -> record.exists(condition.path)
            ConditionOp.BLANK -> trimmed.isEmpty()
            ConditionOp.NOT_BLANK -> trimmed.isNotEmpty()
            ConditionOp.EQUALS -> raw != null && trimmed == operand.trim()
            ConditionOp.NOT_EQUALS -> raw == null || trimmed != operand.trim()
            ConditionOp.EQUALS_IGNORE_CASE -> raw != null && trimmed.equals(operand.trim(), ignoreCase = true)
            ConditionOp.IN -> raw != null && trimmed in members(operand)
            ConditionOp.NOT_IN -> raw == null || trimmed !in members(operand)
            ConditionOp.STARTS_WITH -> raw?.startsWith(operand) == true
            ConditionOp.MATCHES -> raw != null && regex(operand).containsMatchIn(raw)
            ConditionOp.EQUALS_PATH -> trimmed == otherValue(condition, record)
            ConditionOp.NOT_EQUALS_PATH -> trimmed != otherValue(condition, record)
            ConditionOp.ANY_ELEMENT_STARTS_WITH ->
                record.arrayTexts(condition.path)?.any { it.startsWith(operand, ignoreCase = true) } == true
            ConditionOp.EMPTY_OBJECT -> (record.objectSize(condition.path) ?: 0) == 0
            ConditionOp.NON_EMPTY_OBJECT -> (record.objectSize(condition.path) ?: 0) > 0
        }
    }

    private fun members(operand: String): Set<String> = operand.split(",").mapTo(mutableSetOf()) { it.trim() }

    private fun otherValue(
        condition: Condition,
        record: Record,
    ): String {
        val otherPath = requireNotNull(condition.otherPath) { "otherPath required for ${condition.op}" }
        return record.text(otherPath)?.trim().orEmpty()
    }

    /**
     * The value [expr] reads: the first of its paths whose value (per [lookup]) is non-blank, cleaned
     * through its extraction when that matches. Blank (and never extracted) when every path is blank. [lookup] decides what an
     * absent path means — a CSV import treats a missing column as a strategy error and throws.
     */
    fun resolve(
        expr: ValueExpr,
        lookup: (String) -> String?,
    ): String {
        val raw =
            expr.paths
                .asSequence()
                .map { lookup(it).orEmpty() }
                .firstOrNull { it.isNotBlank() } ?: return ""
        return expr.extraction?.let { extract(raw, it) } ?: raw
    }

    /** [extraction] applied to [value]: the substituted template, or null when the pattern doesn't match. */
    fun extract(
        value: String,
        extraction: Extraction,
    ): String? {
        val match = regex(extraction.pattern).find(value) ?: return null
        return substituteTemplate(extraction.outputTemplate, match)
    }
}

/**
 * Substitutes capture-group references in [template] from [match]. Supports `$0` (whole match),
 * `$1`..`$9` (numbered groups) and `${name}` (named groups). Unknown/absent groups become "".
 */
fun substituteTemplate(
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
        // An undefined group name throws rather than returning null; it becomes "" like an absent group.
        val value =
            try {
                (match.groups as? MatchNamedGroupCollection)?.get(template.substring(start + 2, end))?.value.orEmpty()
            } catch (_: IllegalArgumentException) {
                ""
            }
        return value to (end - start + 1)
    }
    if (next.isDigit()) return match.groupValues.getOrNull(next - '0').orEmpty() to 2
    return null
}

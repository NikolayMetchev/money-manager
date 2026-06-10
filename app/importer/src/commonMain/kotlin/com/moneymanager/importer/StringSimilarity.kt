package com.moneymanager.importer

import kotlin.math.max

/**
 * Lightweight string-similarity helpers used by duplicate detection. Bank re-exports of the same
 * transaction often differ only in formatting (e.g. a trailing `20.00` vs `20.00GBP`), so descriptions
 * are compared by normalised edit-distance rather than exact equality.
 */
object StringSimilarity {
    /**
     * Normalised similarity in `[0.0, 1.0]`, where `1.0` means identical (after normalisation).
     * Inputs are trimmed, lower-cased and have internal whitespace collapsed before comparison.
     * Returns `0.0` if either input is blank.
     */
    fun similarity(
        a: String,
        b: String,
    ): Double {
        val na = normalise(a)
        val nb = normalise(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0
        val distance = levenshtein(na, nb)
        return 1.0 - distance.toDouble() / max(na.length, nb.length)
    }

    private fun normalise(s: String): String = s.trim().lowercase().replace(WHITESPACE, " ")

    /**
     * Levenshtein edit distance using two rolling rows: O(a·b) time, O(min(a,b)) space.
     */
    fun levenshtein(
        a: String,
        b: String,
    ): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        // Iterate columns over the shorter string to keep the rolling rows small.
        val (shorter, longer) = if (a.length <= b.length) a to b else b to a
        var previous = IntArray(shorter.length + 1) { it }
        var current = IntArray(shorter.length + 1)

        for (i in 1..longer.length) {
            current[0] = i
            val longerChar = longer[i - 1]
            for (j in 1..shorter.length) {
                val cost = if (longerChar == shorter[j - 1]) 0 else 1
                current[j] =
                    minOf(
                        previous[j] + 1, // deletion
                        current[j - 1] + 1, // insertion
                        previous[j - 1] + cost, // substitution
                    )
            }
            val tmp = previous
            previous = current
            current = tmp
        }
        return previous[shorter.length]
    }

    private val WHITESPACE = Regex("\\s+")
}

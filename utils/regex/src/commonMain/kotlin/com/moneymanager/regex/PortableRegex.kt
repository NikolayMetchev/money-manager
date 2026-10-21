package com.moneymanager.regex

/**
 * A compiled regular expression with Java's syntax and semantics on every platform: inline flags such as `(?i)`,
 * `\Q…\E` quoting, lookbehind, possessive quantifiers. It is [Regex] everywhere but Kotlin/JS, whose [Regex] hands
 * the pattern to the browser's RegExp. There it runs Kotlin's own regex engine (the one Kotlin/Native and Kotlin/Wasm
 * use), so a pattern a person or a strategy saved means the same thing in the browser as on the desktop.
 *
 * Build one with [compileRegex].
 */
expect class PortableRegex(
    pattern: String,
    options: Set<RegexOption>,
) {
    /** The pattern as written, flags and quoting included. */
    val pattern: String

    val options: Set<RegexOption>

    fun containsMatchIn(input: CharSequence): Boolean

    infix fun matches(input: CharSequence): Boolean

    fun replaceFirst(
        input: CharSequence,
        replacement: String,
    ): String
}

/**
 * The first match in [input]. An extension, not a member: a member with [Regex.find]'s defaulted `startIndex` can't be
 * actualized by a typealias.
 */
expect fun PortableRegex.find(input: CharSequence): MatchResult?

package com.moneymanager.regex

/**
 * Compiles a person's or a strategy's [pattern] with Java's regex syntax on every platform (see [PortableRegex]).
 * An invalid pattern throws [IllegalArgumentException].
 */
fun compileRegex(
    pattern: String,
    vararg options: RegexOption,
): PortableRegex = PortableRegex(pattern, options.toSet())

/**
 * A pattern that matches [literal] exactly, quoted as `\Q…\E` the way Java's `Pattern.quote` does on every platform,
 * so a pattern built from it and saved reads the same wherever it was made (Kotlin/JS's [Regex.escape] would
 * backslash-escape instead).
 */
fun escapeRegex(literal: String): String = "\\Q" + literal.replace("\\E", "\\E\\\\E\\Q") + "\\E"

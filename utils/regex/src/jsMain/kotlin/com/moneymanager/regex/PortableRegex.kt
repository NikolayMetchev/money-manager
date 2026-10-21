package com.moneymanager.regex

import com.moneymanager.regex.engine.EngineRegex

actual class PortableRegex actual constructor(
    pattern: String,
    options: Set<RegexOption>,
) {
    internal val regex = EngineRegex(pattern, options)

    actual val pattern: String get() = regex.pattern

    actual val options: Set<RegexOption> get() = regex.options

    actual fun containsMatchIn(input: CharSequence): Boolean = regex.containsMatchIn(input)

    actual infix fun matches(input: CharSequence): Boolean = regex.matches(input)

    actual fun replaceFirst(
        input: CharSequence,
        replacement: String,
    ): String = regex.replaceFirst(input, replacement)

    override fun toString(): String = regex.toString()
}

actual fun PortableRegex.find(input: CharSequence): MatchResult? = regex.find(input)

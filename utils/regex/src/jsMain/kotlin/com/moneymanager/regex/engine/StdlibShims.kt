/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

// Kotlin's own regex engine, adapted as little as possible (see README.md), so it is not linted.
@file:Suppress("ktlint", "detekt:all")

package com.moneymanager.regex.engine

// What the engine reaches in Kotlin/Native's and Kotlin/Wasm's stdlib that Kotlin/JS's doesn't have or keeps internal.

internal const val Char_MIN_SUPPLEMENTARY_CODE_POINT: Int = 0x10000

internal fun Char.Companion.isSupplementaryCodePoint(codePoint: Int): Boolean =
    codePoint >= Char_MIN_SUPPLEMENTARY_CODE_POINT && codePoint <= 0x10FFFF

internal fun Char.Companion.isSurrogatePair(high: Char, low: Char): Boolean = high.isHighSurrogate() && low.isLowSurrogate()

internal fun Char.Companion.toCodePoint(high: Char, low: Char): Int =
    ((high - Char.MIN_HIGH_SURROGATE) shl 10) + (low - Char.MIN_LOW_SURROGATE) + Char_MIN_SUPPLEMENTARY_CODE_POINT

internal fun Char.Companion.toChars(codePoint: Int): CharArray =
    if (isSupplementaryCodePoint(codePoint)) {
        val offset = codePoint - Char_MIN_SUPPLEMENTARY_CODE_POINT
        charArrayOf(Char.MIN_HIGH_SURROGATE + (offset ushr 10), Char.MIN_LOW_SURROGATE + (offset and 0x3FF))
    } else {
        charArrayOf(codePoint.toChar())
    }

internal fun Char.Companion.toLowerCase(codePoint: Int): Int = mapCase(codePoint, Char::lowercaseChar, String::lowercase)

internal fun Char.Companion.toUpperCase(codePoint: Int): Int = mapCase(codePoint, Char::uppercaseChar, String::uppercase)

private fun mapCase(codePoint: Int, char: (Char) -> Char, string: (String) -> String): Int {
    if (!Char.isSupplementaryCodePoint(codePoint)) return char(codePoint.toChar()).code
    val mapped = string(Char.toChars(codePoint).concatToString())
    return if (mapped.length == 2 && Char.isSurrogatePair(mapped[0], mapped[1])) Char.toCodePoint(mapped[0], mapped[1]) else codePoint
}

internal fun digitOf(char: Char, radix: Int): Int = char.digitToIntOrNull(radix) ?: -1

internal fun requireNonNegativeLimit(limit: Int) = require(limit >= 0) { "Limit must be non-negative, but was $limit" }

// Kotlin/Native checks these only when built with assertions on, which release builds aren't.
@Suppress("UNUSED_PARAMETER")
internal fun assert(value: Boolean) = Unit

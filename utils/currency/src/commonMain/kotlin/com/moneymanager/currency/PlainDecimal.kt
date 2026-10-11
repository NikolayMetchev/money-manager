package com.moneymanager.currency

/**
 * Renders a java.math-style decimal string ("1234.5", "-1.2E+3", "5E-7") without an exponent, with at
 * least [minFractionDigits] decimal places. Never rounds: an amount more precise than the currency's
 * minor unit keeps its extra digits rather than silently losing value.
 */
internal fun plainDecimal(
    decimal: String,
    minFractionDigits: Int,
): String {
    val negative = decimal.startsWith("-")
    val unsigned = decimal.removePrefix("-").removePrefix("+")
    val mantissa = unsigned.substringBefore('E').substringBefore('e')
    val exponent = unsigned.substringAfter('E', unsigned.substringAfter('e', "0")).toInt()
    val digits = mantissa.replace(".", "")
    // Position of the decimal point within [digits] once the exponent is applied.
    val point = mantissa.substringBefore('.').length + exponent
    val padded =
        when {
            point <= 0 -> "0".repeat(1 - point) + digits
            point > digits.length -> digits + "0".repeat(point - digits.length)
            else -> digits
        }
    val integerLength = maxOf(point, 1)
    val integerPart = padded.take(integerLength).trimStart('0').ifEmpty { "0" }
    val fractionPart = padded.drop(integerLength).trimEnd('0').padEnd(maxOf(minFractionDigits, 0), '0')
    val body = if (fractionPart.isEmpty()) integerPart else "$integerPart.$fractionPart"
    val isZero = body.all { it == '0' || it == '.' }
    return if (negative && !isZero) "-$body" else body
}

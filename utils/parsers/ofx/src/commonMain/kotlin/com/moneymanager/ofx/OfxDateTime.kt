package com.moneymanager.ofx

import kotlin.math.abs
import kotlin.math.round

/**
 * An OFX date-time (`YYYYMMDD[HHMMSS[.XXX]][[±H[.H][:TZ]]]`) split into ISO parts: [date] `yyyy-MM-dd`, [time]
 * `HH:mm:ss` when the value has one, and [utcOffset] `±HH:mm` when it names one.
 */
data class OfxDateTime(
    val date: String,
    val time: String?,
    val utcOffset: String?,
) {
    companion object {
        private const val DATE_DIGITS = 8
        private const val TIME_DIGITS = 6
        private const val MINUTES_PER_HOUR = 60

        /** [raw] as an [OfxDateTime], or null when it doesn't start with an eight-digit date. */
        fun parse(raw: String): OfxDateTime? {
            val value = raw.trim()
            val digits = value.takeWhile { it.isDigit() }
            if (digits.length < DATE_DIGITS) return null
            val date = "${digits.substring(0, 4)}-${digits.substring(4, 6)}-${digits.substring(6, 8)}"
            val time =
                digits.takeIf { it.length >= DATE_DIGITS + TIME_DIGITS }?.let {
                    "${it.substring(8, 10)}:${it.substring(10, 12)}:${it.substring(12, 14)}"
                }
            val offset =
                value
                    .substringAfter('[', "")
                    .substringBefore(']')
                    .substringBefore(':')
                    .toDoubleOrNull()
                    ?.let(::formatOffset)
            return OfxDateTime(date, time, offset)
        }

        private fun formatOffset(hours: Double): String {
            val totalMinutes = round(abs(hours) * MINUTES_PER_HOUR).toInt()
            val sign = if (hours < 0) "-" else "+"
            val h = (totalMinutes / MINUTES_PER_HOUR).toString().padStart(2, '0')
            val m = (totalMinutes % MINUTES_PER_HOUR).toString().padStart(2, '0')
            return "$sign$h:$m"
        }
    }
}

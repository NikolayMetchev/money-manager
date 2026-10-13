package com.moneymanager.apiimport

/** The current device country as an ISO 3166-1 alpha-2 code (e.g. "GB"), or null if unknown. */
expect fun currentCountryCode(): String?

private val LOCALE_COUNTRY = Regex("^[A-Za-z]{2,3}[_-](?:[A-Za-z]{4}[_-])?([A-Za-z]{2})(?:[._@-].*)?$")

/**
 * The country of a POSIX locale (`en_GB.UTF-8`, `de_DE@euro`) or a BCP 47 tag (`en-GB`, `zh-Hant-TW`), or
 * null when it names none (`C`, `POSIX`, `en`).
 */
fun countryFromLocaleTag(tag: String?): String? =
    tag
        ?.trim()
        ?.let { LOCALE_COUNTRY.matchEntire(it) }
        ?.groupValues
        ?.get(1)
        ?.uppercase()

@file:OptIn(ExperimentalForeignApi::class)

package com.moneymanager.apiimport

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKStringFromUtf16
import platform.windows.GetUserDefaultLocaleName
import platform.windows.LOCALE_NAME_MAX_LENGTH
import platform.windows.WCHARVar

// Windows keeps the user's locale in the registry, not the environment (a BCP 47 name such as "en-GB"); a
// POSIX variable set by MSYS or a test still wins over nothing.
actual fun currentCountryCode(): String? =
    memScoped {
        val buffer = allocArray<WCHARVar>(LOCALE_NAME_MAX_LENGTH)
        if (GetUserDefaultLocaleName(buffer, LOCALE_NAME_MAX_LENGTH) > 0) countryFromLocaleTag(buffer.toKStringFromUtf16()) else null
    } ?: countryFromLocaleEnvironment()

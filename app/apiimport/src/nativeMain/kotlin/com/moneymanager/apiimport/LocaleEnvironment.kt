@file:OptIn(ExperimentalForeignApi::class)

package com.moneymanager.apiimport

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/** The country of the locale the POSIX variables name, in their precedence order (`LC_ALL` overrides the rest). */
internal fun countryFromLocaleEnvironment(): String? =
    listOf("LC_ALL", "LC_MESSAGES", "LANG")
        .firstNotNullOfOrNull { name -> getenv(name)?.toKString()?.takeIf { it.isNotBlank() } }
        .let(::countryFromLocaleTag)

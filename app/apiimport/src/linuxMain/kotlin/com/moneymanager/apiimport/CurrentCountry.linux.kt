package com.moneymanager.apiimport

actual fun currentCountryCode(): String? = countryFromLocaleEnvironment()

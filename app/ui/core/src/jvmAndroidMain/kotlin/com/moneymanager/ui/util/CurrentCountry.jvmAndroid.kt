package com.moneymanager.ui.util

import java.util.Locale

actual fun currentCountryCode(): String? {
    val country = Locale.getDefault().country.uppercase()
    return country.takeIf { it.isNotBlank() }
}

package com.moneymanager.ui.util

import java.util.Locale

actual fun currentCountryCode(): String? = Locale.getDefault().country.uppercase().takeIf { it.isNotBlank() }

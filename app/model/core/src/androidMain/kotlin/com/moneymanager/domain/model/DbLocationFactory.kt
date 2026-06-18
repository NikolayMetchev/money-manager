package com.moneymanager.domain.model

actual fun dbLocationFromString(value: String): DbLocation = DbLocation(value)

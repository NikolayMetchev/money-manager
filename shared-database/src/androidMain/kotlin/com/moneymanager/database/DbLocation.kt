package com.moneymanager.database

actual data class DbLocation(val name: String) {
    actual fun exists() = true
}

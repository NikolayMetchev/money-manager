package com.moneymanager.database

const val DEFAULT_DATABASE_NAME = "money_manager.db"

actual data class DbLocation(val name: String) {
    actual fun exists() = true
}
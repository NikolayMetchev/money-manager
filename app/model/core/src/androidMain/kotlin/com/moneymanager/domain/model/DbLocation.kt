package com.moneymanager.domain.model

actual data class DbLocation(val name: String) {
    /**
     * On Android, checking file existence requires Context.
     * Use [com.moneymanager.database.DatabaseManager.databaseExists] for actual file existence checks.
     * This returns true (assumed to exist or will be created).
     */
    actual fun exists() = true

    override fun toString() = name
}

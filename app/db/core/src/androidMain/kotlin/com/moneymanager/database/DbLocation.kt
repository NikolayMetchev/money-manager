package com.moneymanager.database

actual data class DbLocation(val name: String) {
    /**
     * On Android, checking file existence requires Context.
     * Use [DatabaseManager.databaseExists] for actual file existence checks.
     * This returns true (assumed to exist or will be created).
     */
    actual fun exists() = true
}

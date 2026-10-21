package com.moneymanager.remotestorage.sync

import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.localsettings.LocalSettings

/** Counts rows in [table] (test helper). */
suspend fun MoneyManagerDatabaseWrapper.countRows(table: String): Long =
    rawQuery("SELECT COUNT(*) FROM $table") { it.getLong(0)!! }.awaitAsOne()

/**
 * Appends an audit row so [MoneyManagerDatabaseWrapper.dataChangeToken] advances, simulating a local
 * edit (the controller's "local dirty" signal) without going through the repositories.
 */
suspend fun MoneyManagerDatabaseWrapper.bumpDataChange() {
    execute(
        identifier = null,
        sql =
            "INSERT INTO category_audit (audit_timestamp, audit_type_id, category_id, revision_id, name) " +
                "VALUES (0, (SELECT id FROM audit_type LIMIT 1), 1, 1, 'dirty')",
        parameters = 0,
    )
}

/** In-memory [LocalSettings] for tests. */
class InMemoryLocalSettings : LocalSettings {
    private val map = mutableMapOf<String, String>()

    override fun getString(key: String): String? = map[key]

    override fun putString(
        key: String,
        value: String,
    ) {
        map[key] = value
    }

    override fun remove(key: String) {
        map.remove(key)
    }
}

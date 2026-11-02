package com.moneymanager.remotestorage.sync

import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.localsettings.LocalSettings
import com.moneymanager.test.database.read.testReads
import com.moneymanager.test.database.write.testWrites

/** Counts the currencies (test helper). */
suspend fun MoneyManagerDatabaseWrapper.countCurrencies(): Long = testReads.rowCountSelectQueries.countCurrencies().awaitAsOne()

/**
 * Appends an audit row so [MoneyManagerDatabaseWrapper.dataChangeToken] advances, simulating a local
 * edit (the controller's "local dirty" signal) without going through the repositories.
 */
suspend fun MoneyManagerDatabaseWrapper.bumpDataChange() {
    testWrites.fixtureWriteQueries.insertDirtyCategoryAudit()
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

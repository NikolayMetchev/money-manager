package com.moneymanager.database.write

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.db.SqlDriver
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.database.sql.write.MoneyManagerDatabase as WriteDatabase

/**
 * Composes the read and write SQLDelight databases over a single [SqlDriver].
 *
 * The schema and every `*Select.sq` live in :app:db:read (generating the read [MoneyManagerDatabase]
 * delegated below — this exposes the `*SelectQueries`). Every `*Write.sq` lives in :app:db:write
 * (generating a separate write database in com.moneymanager.database.sql.write). Both wrap the SAME
 * driver, so writes through [writeDb] are seen by read-side query listeners. The `*WriteQueries` are
 * re-exposed under their original names so existing repository/seed code (`database.accountWriteQueries`,
 * `database.accountSelectQueries`, …) compiles unchanged.
 */
class MoneyManagerDatabaseWrapper(
    // The browser's DatabaseManager snapshots through its own driver.
    val driver: SqlDriver,
) : MoneyManagerDatabase by MoneyManagerDatabase(driver) {
    private val writeDb = WriteDatabase(driver)

    val accountAttributeWriteQueries get() = writeDb.accountAttributeWriteQueries
    val accountMappingWriteQueries get() = writeDb.accountMappingWriteQueries
    val accountMergeWriteQueries get() = writeDb.accountMergeWriteQueries
    val accountWriteQueries get() = writeDb.accountWriteQueries
    val creationModeWriteQueries get() = writeDb.creationModeWriteQueries
    val assetWriteQueries get() = writeDb.assetWriteQueries
    val cryptoWriteQueries get() = writeDb.cryptoWriteQueries
    val apiImportStrategyWriteQueries get() = writeDb.apiImportStrategyWriteQueries
    val apiSessionWriteQueries get() = writeDb.apiSessionWriteQueries
    val attributeTypeWriteQueries get() = writeDb.attributeTypeWriteQueries
    val categoryWriteQueries get() = writeDb.categoryWriteQueries
    val csvImportStrategyWriteQueries get() = writeDb.csvImportStrategyWriteQueries
    val csvImportWriteQueries get() = writeDb.csvImportWriteQueries
    val currencyWriteQueries get() = writeDb.currencyWriteQueries
    val deviceWriteQueries get() = writeDb.deviceWriteQueries
    val entitySourceWriteQueries get() = writeDb.entitySourceWriteQueries
    val exchangeOrderWriteQueries get() = writeDb.exchangeOrderWriteQueries
    val importDirectoryWriteQueries get() = writeDb.importDirectoryWriteQueries
    val maintenanceWriteQueries get() = writeDb.maintenanceWriteQueries
    val personAttributeWriteQueries get() = writeDb.personAttributeWriteQueries
    val personWriteQueries get() = writeDb.personWriteQueries
    val passThroughAccountWriteQueries get() = writeDb.passThroughAccountWriteQueries
    val holidayCalendarWriteQueries get() = writeDb.holidayCalendarWriteQueries
    val reconciliationLinkWriteQueries get() = writeDb.reconciliationLinkWriteQueries
    val relationshipTypeWriteQueries get() = writeDb.relationshipTypeWriteQueries
    val settingsWriteQueries get() = writeDb.settingsWriteQueries
    val tradeWriteQueries get() = writeDb.tradeWriteQueries
    val transactionIdWriteQueries get() = writeDb.transactionIdWriteQueries
    val transferAttributeWriteQueries get() = writeDb.transferAttributeWriteQueries
    val relationshipWriteQueries get() = writeDb.relationshipWriteQueries
    val transferWriteQueries get() = writeDb.transferWriteQueries

    /**
     * Closes the underlying driver/connection. Used for short-lived databases opened off the main
     * session (e.g. rehydrating a remote snapshot) so the file can be safely reopened afterwards.
     */
    fun close() = driver.close()

    /**
     * A token reflecting how much logical, syncable state has changed since it was last captured. It
     * folds together three things:
     *  - the total number of rows across the append-only `*_audit` tables (every entity
     *    create/update/delete appends one),
     *  - the values of the singleton `settings` row (its default currency and location), which is
     *    excluded from the audit trail but is still part of the synced database and must register as a
     *    change — otherwise e.g. setting the default currency would never be detected as unsynced, and
     *  - a rolling hash of the `account_mapping` table, which is user-managed first-class data but is
     *    not audited, so its edits must still register as a change (the same goes for
     *    `reconciliation_account_link`, `trade_attribute` and the relationship tables).
     *
     * Derived materialized-view rebuilds append no audit rows and don't touch settings, so this stays a
     * stable "has the data changed since last sync?" signal that ignores our own view maintenance. Only
     * equality of two tokens is meaningful (it is a hash, not a counter).
     */
    suspend fun dataChangeToken(): Long {
        val auditRowCount =
            dataChangeSelectQueries
                .auditRowCount()
                .awaitAsOne()
                .SUM
                .orZero()
        return foldReconciliationTablesInto(foldAccountMappingsInto(foldSettingsInto(auditRowCount)))
    }

    /**
     * Mixes the non-audited `account_mapping` table into [base] with a rolling hash. Row count catches
     * inserts/deletes; summing ids catches which rows exist; summing updated_at catches in-place edits.
     */
    private suspend fun foldAccountMappingsInto(base: Long): Long =
        dataChangeSelectQueries
            .accountMappingFingerprint { count, ids, updatedAt -> listOf(count, ids.orZero(), updatedAt.orZero()) }
            .awaitAsOneOrNull()
            .mixInto(base)

    /**
     * Mixes the non-audited `reconciliation_account_link`, `trade_attribute` and relationship tables into
     * [base], like [foldAccountMappingsInto]: their edits must register as unsynced. A relationship made by
     * hand touches no audited table.
     */
    private suspend fun foldReconciliationTablesInto(base: Long): Long {
        val links =
            dataChangeSelectQueries
                .reconciliationLinkFingerprint { count, ids, createdAt -> listOf(count, ids.orZero(), createdAt.orZero()) }
                .awaitAsOneOrNull()
        // trade_attribute has no updated_at, and a value can change in place (e.g. an exclusion reason), so
        // fold every row's content; SQLite has no string hash, hence the Kotlin fold. The table is small.
        val tradeAttributes =
            dataChangeSelectQueries
                .tradeAttributeRows { tradeId, typeId, value -> listOf(tradeId, typeId, value.hashCode().toLong()) }
                .awaitAsList()
        // Relationships are never edited in place, only created and deleted, so ids catch every change.
        val relationships =
            dataChangeSelectQueries
                .relationshipMemberFingerprint { count, ids, roles -> listOf(count, ids.orZero(), roles.orZero()) }
                .awaitAsOneOrNull()
        return tradeAttributes.fold(relationships.mixInto(links.mixInto(base))) { token, row -> row.mixInto(token) }
    }

    /**
     * Mixes the non-audited `settings` row (id = 1) into [base] with a rolling hash, so a change to any
     * tracked setting (the default currency) yields a different token even though the
     * settings table appends no audit rows. A missing row leaves [base] unchanged.
     */
    private suspend fun foldSettingsInto(base: Long): Long =
        dataChangeSelectQueries
            .settingsFingerprint { currency, location -> listOf(currency.orZero(), location.hashCode().toLong()) }
            .awaitAsOneOrNull()
            .mixInto(base)

    /** Folds one row's values into [token] with the rolling hash; a missing row leaves it unchanged. */
    private fun List<Long>?.mixInto(token: Long): Long = this?.fold(token) { acc, value -> acc * SETTINGS_HASH_PRIME + value } ?: token

    /**
     * Enables creation mode by inserting a row into _creation_mode.
     * When creation mode is active, attribute triggers record audit but don't bump revision.
     * Use this when creating a transfer with initial attributes.
     * Must be paired with [endCreationMode] to restore normal trigger behavior.
     */
    suspend fun beginCreationMode() {
        creationModeWriteQueries.beginCreationMode()
    }

    /**
     * Disables creation mode by clearing _creation_mode table.
     * Restores normal trigger behavior (bump revision on attribute changes).
     */
    suspend fun endCreationMode() {
        creationModeWriteQueries.endCreationMode()
    }

    private fun Number?.orZero(): Long = this?.toLong() ?: 0L

    private companion object {
        /** Odd prime used to mix settings values into the data-change token (collision-resistant fold). */
        const val SETTINGS_HASH_PRIME = 1_000_003L
    }
}

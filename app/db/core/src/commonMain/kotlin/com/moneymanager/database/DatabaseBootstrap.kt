package com.moneymanager.database

import app.cash.sqldelight.async.coroutines.await
import com.moneymanager.database.sql.seed.MoneyManagerDatabase
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.CurrencyExportEntry
import com.moneymanager.tasks.Progress

/** How many steps opening a database reports, the same for every [DatabaseManager] that creates its own schema. */
internal const val OPEN_STEPS = 6

/**
 * Creates the schema in [database], a new, empty one, and seeds it with [currencies], reporting steps 4 and 5 of
 * [OPEN_STEPS]. The caller deletes the database when this throws: one left behind would open next time as an
 * existing, empty database.
 */
internal suspend fun createSchema(
    database: MoneyManagerDatabaseWrapper,
    currencies: () -> List<CurrencyExportEntry>,
    onProgress: (Progress) -> Unit,
) {
    // One transaction for the whole creation: on its own every statement and seeded currency commits
    // separately, about a thousand syncs to disk, which takes seconds where syncing is slow (Windows).
    database.transaction {
        onProgress(Progress.counted("Creating the database schema...", 4, OPEN_STEPS))
        // Lookups and the system device are seeded inside Schema.create (the :app:db:seed module).
        MoneyManagerDatabase.Schema.create(database.driver).await()
        // The app installs the rest from the strategy catalog; only tests seed any here.
        onProgress(Progress.counted("Preparing repositories...", 5, OPEN_STEPS))
        DatabaseConfig.seedCurrencies(database, currencies())
    }
}

/** Steps 4 and 5 of [OPEN_STEPS] for a database that already exists. */
internal fun reportExisting(onProgress: (Progress) -> Unit) {
    onProgress(Progress.counted("Checking the database schema...", 4, OPEN_STEPS))
    onProgress(Progress.counted("Preparing repositories...", 5, OPEN_STEPS))
}

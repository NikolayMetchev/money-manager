package com.moneymanager.database

/**
 * Represents the current state of the database in the application.
 * Used to manage database lifecycle and UI state.
 */
sealed class DatabaseState {
    /**
     * No database is currently loaded.
     * The UI should show database selection/creation options.
     */
    data object NoDatabaseSelected : DatabaseState()

    /**
     * A database is successfully loaded and ready to use.
     *
     * @property location The location of the loaded database
     * @property repositories The repository set bound to this database
     */
    data class DatabaseLoaded(
        val location: DbLocation,
        val repositories: RepositorySet,
    ) : DatabaseState()

    /**
     * An error occurred while managing the database.
     *
     * @property error The error that occurred
     */
    data class Error(val error: Throwable) : DatabaseState()
}

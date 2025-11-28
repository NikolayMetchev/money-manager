package com.moneymanager.database

/**
 * Factory for creating repository instances from a database.
 * Repositories are bound to a specific database instance.
 */
interface RepositoryFactory {
    /**
     * Creates a complete set of repositories for the given database.
     *
     * @param database The database instance to use for repository operations
     * @return A RepositorySet containing all application repositories
     */
    fun createRepositories(database: MoneyManagerDatabase): RepositorySet
}

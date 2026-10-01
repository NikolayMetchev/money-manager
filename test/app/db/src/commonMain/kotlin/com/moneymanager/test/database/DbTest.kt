package com.moneymanager.test.database

import com.moneymanager.database.di.DatabaseComponent
import com.moneymanager.database.sql.entitySource.EntitySourceWriteQueries
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.ApiCredentialId
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.time.Instant

open class DbTest {
    protected lateinit var database: MoneyManagerDatabaseWrapper
    private lateinit var testDbLocation: DbLocation
    protected lateinit var repositories: DatabaseComponent
    protected lateinit var entitySourceQueries: EntitySourceWriteQueries

    /** Override (= true) in tests that assert against the full platform currency list. */
    protected open val seedAllCurrencies: Boolean = false

    /**
     * Override (= true) in tests that exercise the built-in strategies/pass-throughs. Fresh databases
     * no longer seed them (they are installed on demand from the strategy-library catalog), so opting
     * in installs all of them through the engine before the test body runs.
     */
    protected open val installBuiltInStrategies: Boolean = false

    @BeforeTest
    fun setup() =
        runTest {
            testDbLocation = createTestDatabaseLocation()
            database = createTestDatabaseManager(seedAllCurrencies).openDatabase(testDbLocation)
            repositories = DatabaseComponent.create(database)
            entitySourceQueries = database.entitySourceWriteQueries
            if (installBuiltInStrategies) {
                repositories.installBuiltInCsvStrategies()
                repositories.installBuiltInApiStrategies()
                repositories.installBuiltInPassThroughs()
            }
        }

    @AfterTest
    fun cleanup() {
        deleteTestDatabase(testDbLocation)
    }

    /**
     * The connection row for the installed API strategy named [strategyName], created on first use. API
     * secrets live in the credential vault, so tests that drive a download pass the token directly.
     */
    protected suspend fun apiConnection(
        strategyName: String,
        createdAt: Instant,
    ): ApiCredentialId {
        val strategy =
            repositories.apiImportStrategyRepository
                .getAllStrategies()
                .first()
                .single { it.name == strategyName }
        return repositories.apiSessionRepository.ensureCredential(strategy.id, createdAt)
    }

    /**
     * Helper to create a transfer in tests.
     * Uses SampleGenerator source type for test data.
     * Returns the created transfer with its database-generated ID.
     */
    protected suspend fun createTransfer(transfer: Transfer): Transfer {
        repositories.transactionRepository.createTransfers(
            transfers = listOf(transfer),
            sources = listOf(Source.SampleGenerator),
        )
        // Query back the created transfer by its details (timestamp + description should be unique enough for tests)
        val allTransfers =
            repositories.transactionRepository
                .getTransactionsByDateRange(
                    startDate = transfer.timestamp,
                    endDate = transfer.timestamp,
                ).first()
        return allTransfers.first { it.description == transfer.description }
    }
}

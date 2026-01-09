@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.test.database

import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.SampleGeneratorSourceRecorder
import com.moneymanager.database.sql.TransferSourceQueries
import com.moneymanager.di.AppComponent
import com.moneymanager.di.database.DatabaseComponent
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.Transfer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

open class DbTest {
    protected lateinit var database: MoneyManagerDatabaseWrapper
    private lateinit var testDbLocation: DbLocation
    protected lateinit var repositories: DatabaseComponent
    protected lateinit var transferSourceQueries: TransferSourceQueries

    @BeforeTest
    fun setup() =
        runTest {
            testDbLocation = createTestDatabaseLocation()
            val component = AppComponent.create(createTestAppComponentParams())
            val databaseManager = component.databaseManager
            database = databaseManager.openDatabase(testDbLocation)
            repositories = DatabaseComponent.create(database)
            transferSourceQueries = database.transferSourceQueries
        }

    @AfterTest
    fun cleanup() {
        deleteTestDatabase(testDbLocation)
    }

    /**
     * Helper to create a transfer in tests.
     * Uses SampleGenerator source type for test data.
     * Returns the created transfer with its database-generated ID.
     */
    protected suspend fun createTransfer(transfer: Transfer): Transfer {
        val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
        repositories.transactionRepository.createTransfers(
            transfers = listOf(transfer),
            sourceRecorder = SampleGeneratorSourceRecorder(transferSourceQueries, deviceId),
        )
        // Query back the created transfer by its details (timestamp + description should be unique enough for tests)
        val allTransfers =
            repositories.transactionRepository.getTransactionsByDateRange(
                startDate = transfer.timestamp,
                endDate = transfer.timestamp,
            ).first()
        return allTransfers.first { it.description == transfer.description }
    }
}

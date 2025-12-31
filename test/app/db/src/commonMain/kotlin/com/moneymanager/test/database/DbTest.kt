@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.test.database

import com.moneymanager.database.DbLocation
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.RepositorySet
import com.moneymanager.database.SampleGeneratorSourceRecorder
import com.moneymanager.di.AppComponent
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferWithAttributes
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

abstract class DbTest {
    protected lateinit var database: MoneyManagerDatabaseWrapper
    private lateinit var testDbLocation: DbLocation
    protected lateinit var repositories: RepositorySet

    @BeforeTest
    fun setup() =
        runTest {
            testDbLocation = createTestDatabaseLocation()
            val component = AppComponent.create(createTestAppComponentParams())
            val databaseManager = component.databaseManager
            database = databaseManager.openDatabase(testDbLocation)
            repositories = RepositorySet(database)
        }

    @AfterTest
    fun cleanup() {
        deleteTestDatabase(testDbLocation)
    }

    /**
     * Helper to create a transfer in tests.
     * Uses SampleGenerator source type for test data.
     */
    protected suspend fun createTransfer(transfer: Transfer) {
        val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
        repositories.transactionRepository.createTransfersWithAttributesAndSources(
            transfersWithAttributes = listOf(TransferWithAttributes(transfer, emptyList())),
            sourceRecorder = SampleGeneratorSourceRecorder(repositories.transferSourceQueries, deviceId),
        )
    }
}

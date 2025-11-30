package com.moneymanager.database.repository

import com.moneymanager.database.RepositorySet
import com.moneymanager.database.createTestDatabaseLocation
import com.moneymanager.database.deleteTestDatabase
import com.moneymanager.di.AppComponent
import com.moneymanager.di.createTestAppComponentParams
import com.moneymanager.domain.repository.AssetRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AssetRepositoryImplTest {
    private lateinit var repository: AssetRepository
    private lateinit var testDbLocation: com.moneymanager.database.DbLocation

    @BeforeTest
    fun setup() =
        runTest {
            // Create temporary database file
            testDbLocation = createTestDatabaseLocation()

            // Create app component
            val component = AppComponent.create(createTestAppComponentParams())
            val databaseManager = component.databaseManager

            // Open file-based database for testing
            val database = databaseManager.openDatabase(testDbLocation)
            val repositories = RepositorySet(database)

            repository = repositories.assetRepository
        }

    @AfterTest
    fun cleanup() {
        deleteTestDatabase(testDbLocation)
    }

    // UPSERT TESTS

    @Test
    fun `upsertAssetByName should create new asset when name does not exist`() =
        runTest {
            // When
            val assetId = repository.upsertAssetByName("USD")

            // Then
            assertTrue(assetId > 0, "Generated ID should be positive")

            val assets = repository.getAllAssets().first()
            assertEquals(1, assets.size)
            assertEquals("USD", assets[0].name)
            assertEquals(assetId, assets[0].id)
        }

    @Test
    fun `upsertAssetByName should return existing ID when asset already exists`() =
        runTest {
            // Given
            val firstId = repository.upsertAssetByName("EUR")

            // When
            val secondId = repository.upsertAssetByName("EUR")

            // Then
            assertEquals(firstId, secondId, "Should return same ID for existing asset")

            val assets = repository.getAllAssets().first()
            assertEquals(1, assets.size, "Should not create duplicate asset")
        }

    @Test
    fun `upsertAssetByName should not modify existing asset data`() =
        runTest {
            // Given
            val originalId = repository.upsertAssetByName("GBP")
            val originalAsset = repository.getAssetById(originalId).first()
            assertNotNull(originalAsset)

            // When - upsert the same name again
            val newId = repository.upsertAssetByName("GBP")

            // Then
            assertEquals(originalId, newId, "ID should not change")

            val assetAfterUpsert = repository.getAssetById(originalId).first()
            assertNotNull(assetAfterUpsert)
            assertEquals(originalAsset.name, assetAfterUpsert.name, "Name should not change")
            assertEquals(originalAsset.id, assetAfterUpsert.id, "ID should not change")
        }

    @Test
    fun `upsertAssetByName should handle multiple different assets`() =
        runTest {
            // When
            val usdId = repository.upsertAssetByName("USD")
            val eurId = repository.upsertAssetByName("EUR")
            val gbpId = repository.upsertAssetByName("GBP")

            // Then
            val assets = repository.getAllAssets().first()
            assertEquals(3, assets.size)

            val ids = assets.map { it.id }.toSet()
            assertTrue(ids.contains(usdId))
            assertTrue(ids.contains(eurId))
            assertTrue(ids.contains(gbpId))

            val names = assets.map { it.name }.toSet()
            assertEquals(setOf("USD", "EUR", "GBP"), names)
        }

    @Test
    fun `upsertAssetByName should be case sensitive`() =
        runTest {
            // When
            val lowercaseId = repository.upsertAssetByName("usd")
            val uppercaseId = repository.upsertAssetByName("USD")

            // Then
            val assets = repository.getAllAssets().first()
            assertEquals(2, assets.size, "Should create two separate assets for different cases")
            assertTrue(lowercaseId != uppercaseId, "IDs should be different")
        }

    @Test
    fun `upsertAssetByName should handle rapid consecutive calls for same name`() =
        runTest {
            // When - simulate rapid upserts
            val id1 = repository.upsertAssetByName("BTC")
            val id2 = repository.upsertAssetByName("BTC")
            val id3 = repository.upsertAssetByName("BTC")

            // Then
            assertEquals(id1, id2)
            assertEquals(id2, id3)

            val assets = repository.getAllAssets().first()
            assertEquals(1, assets.size, "Should only have one asset despite multiple upserts")
        }

    // GET ALL ASSETS TESTS

    @Test
    fun `getAllAssets should return empty list when no assets exist`() =
        runTest {
            val assets = repository.getAllAssets().first()
            assertTrue(assets.isEmpty())
        }

    @Test
    fun `getAllAssets should return all assets`() =
        runTest {
            // Given
            repository.upsertAssetByName("USD")
            repository.upsertAssetByName("EUR")
            repository.upsertAssetByName("GBP")

            // When
            val assets = repository.getAllAssets().first()

            // Then
            assertEquals(3, assets.size)
            val names = assets.map { it.name }.toSet()
            assertEquals(setOf("USD", "EUR", "GBP"), names)
        }
}

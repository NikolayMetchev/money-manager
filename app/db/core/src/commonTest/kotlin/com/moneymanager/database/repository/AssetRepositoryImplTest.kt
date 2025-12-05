package com.moneymanager.database.repository

import com.moneymanager.database.DatabaseConfig
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

    // Number of seeded currencies from DatabaseConfig
    private val seededCurrencyCount = DatabaseConfig.defaultCurrencies.size

    // UPSERT TESTS

    @Test
    fun `upsertAssetByName should create new asset when name does not exist`() =
        runTest {
            // When - use a currency not in the seeded list
            val assetId = repository.upsertAssetByName("CHF")

            // Then
            assertTrue(assetId > 0, "Generated ID should be positive")

            val asset = repository.getAssetById(assetId).first()
            assertNotNull(asset)
            assertEquals("CHF", asset.name)
        }

    @Test
    fun `upsertAssetByName should return existing ID when asset already exists`() =
        runTest {
            // Given - EUR is already seeded
            val initialAssets = repository.getAllAssets().first()
            val initialCount = initialAssets.size

            // When - upsert an already seeded currency
            val eurId = repository.upsertAssetByName("EUR")

            // Then
            val assetsAfter = repository.getAllAssets().first()
            assertEquals(initialCount, assetsAfter.size, "Should not create duplicate asset")

            // Upsert again should return same ID
            val secondEurId = repository.upsertAssetByName("EUR")
            assertEquals(eurId, secondEurId, "Should return same ID for existing asset")
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
            // When - upsert seeded currencies (should return existing IDs)
            val usdId = repository.upsertAssetByName("USD")
            val eurId = repository.upsertAssetByName("EUR")
            val gbpId = repository.upsertAssetByName("GBP")

            // Then - should have at least the seeded currencies
            val assets = repository.getAllAssets().first()
            assertEquals(seededCurrencyCount, assets.size)

            val ids = assets.map { it.id }.toSet()
            assertTrue(ids.contains(usdId))
            assertTrue(ids.contains(eurId))
            assertTrue(ids.contains(gbpId))

            val names = assets.map { it.name }.toSet()
            assertTrue(names.containsAll(setOf("USD", "EUR", "GBP")))
        }

    @Test
    fun `upsertAssetByName should be case sensitive`() =
        runTest {
            // Given - USD is already seeded
            val initialCount = repository.getAllAssets().first().size

            // When
            val lowercaseId = repository.upsertAssetByName("usd")
            val uppercaseId = repository.upsertAssetByName("USD")

            // Then - lowercase should be new, uppercase should be existing
            val assets = repository.getAllAssets().first()
            assertEquals(
                initialCount + 1,
                assets.size,
                "Should create one new asset for lowercase (USD already exists)",
            )
            assertTrue(lowercaseId != uppercaseId, "IDs should be different")
        }

    @Test
    fun `upsertAssetByName should handle rapid consecutive calls for same name`() =
        runTest {
            // Given
            val initialCount = repository.getAllAssets().first().size

            // When - simulate rapid upserts for a new currency
            val id1 = repository.upsertAssetByName("BTC")
            val id2 = repository.upsertAssetByName("BTC")
            val id3 = repository.upsertAssetByName("BTC")

            // Then
            assertEquals(id1, id2)
            assertEquals(id2, id3)

            val assets = repository.getAllAssets().first()
            assertEquals(
                initialCount + 1,
                assets.size,
                "Should only add one new asset despite multiple upserts",
            )
        }

    // GET ALL ASSETS TESTS

    @Test
    fun `getAllAssets should return seeded currencies for new database`() =
        runTest {
            val assets = repository.getAllAssets().first()
            assertEquals(seededCurrencyCount, assets.size)

            val names = assets.map { it.name }.toSet()
            assertEquals(DatabaseConfig.defaultCurrencies.toSet(), names)
        }

    @Test
    fun `getAllAssets should return all assets including seeded and new`() =
        runTest {
            // Given - add a new currency not in seeded list
            repository.upsertAssetByName("CHF")

            // When
            val assets = repository.getAllAssets().first()

            // Then - should have seeded currencies plus the new one
            assertEquals(seededCurrencyCount + 1, assets.size)
            val names = assets.map { it.name }.toSet()
            assertTrue(names.containsAll(DatabaseConfig.defaultCurrencies))
            assertTrue(names.contains("CHF"))
        }
}

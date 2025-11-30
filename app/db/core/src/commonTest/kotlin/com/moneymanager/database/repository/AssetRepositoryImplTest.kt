package com.moneymanager.database.repository

import com.moneymanager.database.IN_MEMORY_DATABASE
import com.moneymanager.database.RepositorySet
import com.moneymanager.di.AppComponent
import com.moneymanager.di.createTestAppComponentParams
import com.moneymanager.domain.repository.AssetRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AssetRepositoryImplTest {
    private lateinit var repository: AssetRepository

    @BeforeTest
    fun setup() =
        runTest {
            // Create app component
            val component = AppComponent.create(createTestAppComponentParams())
            val databaseManager = component.databaseManager

            // Open in-memory database for testing
            val database = databaseManager.openDatabase(IN_MEMORY_DATABASE)
            val repositories = RepositorySet(database)

            repository = repositories.assetRepository
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

    // GET ASSET BY ID TESTS

    @Test
    fun `getAssetById should return correct asset`() =
        runTest {
            // Given
            val assetId = repository.upsertAssetByName("JPY")

            // When
            val asset = repository.getAssetById(assetId).first()

            // Then
            assertNotNull(asset)
            assertEquals(assetId, asset.id)
            assertEquals("JPY", asset.name)
        }

    @Test
    fun `getAssetById should return null for non-existent id`() =
        runTest {
            // When
            val asset = repository.getAssetById(999L).first()

            // Then
            assertEquals(null, asset)
        }

    // UPDATE ASSET TESTS

    @Test
    fun `updateAsset should modify existing asset`() =
        runTest {
            // Given
            val originalId = repository.upsertAssetByName("Bitcoin")
            val asset = repository.getAssetById(originalId).first()
            assertNotNull(asset)

            // When
            val updatedAsset = asset.copy(name = "BTC")
            repository.updateAsset(updatedAsset)

            // Then
            val result = repository.getAssetById(originalId).first()
            assertNotNull(result)
            assertEquals("BTC", result.name)
            assertEquals(originalId, result.id)
        }

    // DELETE ASSET TESTS

    @Test
    fun `deleteAsset should remove asset from database`() =
        runTest {
            // Given
            val assetId = repository.upsertAssetByName("ETH")

            // When
            repository.deleteAsset(assetId)

            // Then
            val asset = repository.getAssetById(assetId).first()
            assertEquals(null, asset)

            val allAssets = repository.getAllAssets().first()
            assertTrue(allAssets.isEmpty())
        }

    @Test
    fun `deleteAsset should not affect other assets`() =
        runTest {
            // Given
            val usdId = repository.upsertAssetByName("USD")
            val eurId = repository.upsertAssetByName("EUR")

            // When
            repository.deleteAsset(usdId)

            // Then
            val usdAsset = repository.getAssetById(usdId).first()
            assertEquals(null, usdAsset)

            val eurAsset = repository.getAssetById(eurId).first()
            assertNotNull(eurAsset)
            assertEquals("EUR", eurAsset.name)

            val allAssets = repository.getAllAssets().first()
            assertEquals(1, allAssets.size)
        }

    @Test
    fun `deleteAsset should succeed for non-existent id`() =
        runTest {
            // Should not throw an exception
            repository.deleteAsset(999L)
        }

    // EDGE CASES AND INTEGRATION TESTS

    @Test
    fun `should handle assets with special characters`() =
        runTest {
            val specialNames = listOf("US$", "€ EUR", "£GBP", "¥JPY", "₿BTC")

            specialNames.forEach { name ->
                val assetId = repository.upsertAssetByName(name)
                val asset = repository.getAssetById(assetId).first()

                assertNotNull(asset)
                assertEquals(name, asset.name)
            }

            val allAssets = repository.getAllAssets().first()
            assertEquals(specialNames.size, allAssets.size)
        }

    @Test
    fun `should handle very long asset names`() =
        runTest {
            val longName = "A".repeat(100)

            val assetId = repository.upsertAssetByName(longName)
            val asset = repository.getAssetById(assetId).first()

            assertNotNull(asset)
            assertEquals(longName, asset.name)
        }

    @Test
    fun `should handle empty string as asset name`() =
        runTest {
            val assetId = repository.upsertAssetByName("")
            val asset = repository.getAssetById(assetId).first()

            assertNotNull(asset)
            assertEquals("", asset.name)
        }

    @Test
    fun `should maintain referential integrity with multiple upserts and deletes`() =
        runTest {
            // Create assets
            val usdId = repository.upsertAssetByName("USD")
            val eurId = repository.upsertAssetByName("EUR")

            // Upsert existing
            val usdId2 = repository.upsertAssetByName("USD")
            assertEquals(usdId, usdId2)

            // Delete one
            repository.deleteAsset(eurId)

            // Verify state
            val assets = repository.getAllAssets().first()
            assertEquals(1, assets.size)
            assertEquals("USD", assets[0].name)

            // Re-create deleted asset - should get new ID
            val eurId2 = repository.upsertAssetByName("EUR")
            assertTrue(eurId2 > 0)

            val finalAssets = repository.getAllAssets().first()
            assertEquals(2, finalAssets.size)
        }
}

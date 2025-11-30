package com.moneymanager.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.repository.AssetRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class AssetsScreenTest {
    @Test
    fun assetsScreen_displaysEmptyState_whenNoAssets() =
        runComposeUiTest {
            // Given
            val repository = FakeAssetRepository(emptyList())

            // When
            setContent {
                AssetsScreen(assetRepository = repository)
            }

            // Then
            onNodeWithText("Your Assets").assertIsDisplayed()
            onNodeWithText("No assets yet. Add your first asset!").assertIsDisplayed()
        }

    @Test
    fun assetsScreen_displaysAssets_whenAssetsExist() =
        runComposeUiTest {
            // Given
            val assets =
                listOf(
                    Asset(
                        id = 1L,
                        name = "US Dollar",
                    ),
                    Asset(
                        id = 2L,
                        name = "Euro",
                    ),
                )
            val repository = FakeAssetRepository(assets)

            // When
            setContent {
                AssetsScreen(assetRepository = repository)
            }

            // Then
            onNodeWithText("US Dollar").assertIsDisplayed()
            onNodeWithText("Euro").assertIsDisplayed()
        }

    @Test
    fun assetsScreen_displaysFloatingActionButton() =
        runComposeUiTest {
            // Given
            val repository = FakeAssetRepository(emptyList())

            // When
            setContent {
                AssetsScreen(assetRepository = repository)
            }

            // Then
            onNodeWithText("+").assertIsDisplayed()
        }

    @Test
    fun assetsScreen_opensCreateDialog_whenFabClicked() =
        runComposeUiTest {
            // Given
            val repository = FakeAssetRepository(emptyList())

            // When
            setContent {
                AssetsScreen(assetRepository = repository)
            }

            onNodeWithText("+").performClick()

            // Then
            onNodeWithText("Create New Asset").assertIsDisplayed()
            onNodeWithText("Asset Name").assertIsDisplayed()
        }

    @Test
    fun assetCard_displaysAssetInformation() =
        runComposeUiTest {
            // Given
            val asset =
                Asset(
                    id = 1L,
                    name = "Bitcoin",
                )
            val repository = FakeAssetRepository(listOf(asset))

            // When
            setContent {
                AssetsScreen(assetRepository = repository)
            }

            // Then
            onNodeWithText("Bitcoin").assertIsDisplayed()
        }

    @Test
    fun assetCard_opensDeleteDialog_whenDeleteButtonClicked() =
        runComposeUiTest {
            // Given
            val asset =
                Asset(
                    id = 1L,
                    name = "Test Asset",
                )
            val repository = FakeAssetRepository(listOf(asset))

            // When
            setContent {
                AssetsScreen(assetRepository = repository)
            }

            // Click the delete button (trash icon)
            onNodeWithText("🗑️").performClick()

            // Then
            onNodeWithText("Delete Asset?").assertIsDisplayed()
            onNodeWithText("Are you sure you want to delete \"Test Asset\"?").assertIsDisplayed()
        }

    @Test
    fun createAssetDialog_validatesRequiredFields() =
        runComposeUiTest {
            // Given
            val repository = FakeAssetRepository(emptyList())

            // When
            setContent {
                AssetsScreen(assetRepository = repository)
            }

            // Open dialog
            onNodeWithText("+").performClick()

            // Try to create without filling fields
            onNodeWithText("Create").performClick()

            // Then
            onNodeWithText("Asset name is required").assertIsDisplayed()
        }

    @Test
    fun createAssetDialog_canBeDismissed() =
        runComposeUiTest {
            // Given
            val repository = FakeAssetRepository(emptyList())

            // When
            setContent {
                AssetsScreen(assetRepository = repository)
            }

            // Open dialog
            onNodeWithText("+").performClick()
            onNodeWithText("Create New Asset").assertIsDisplayed()

            // Click cancel
            onNodeWithText("Cancel").performClick()

            // Then - dialog should be dismissed
            onNodeWithText("Create New Asset").assertDoesNotExist()
        }

    @Test
    fun deleteAssetDialog_canBeDismissed() =
        runComposeUiTest {
            // Given
            val asset =
                Asset(
                    id = 1L,
                    name = "Test Asset",
                )
            val repository = FakeAssetRepository(listOf(asset))

            // When
            setContent {
                AssetsScreen(assetRepository = repository)
            }

            // Open delete dialog
            onNodeWithText("🗑️").performClick()
            onNodeWithText("Delete Asset?").assertIsDisplayed()

            // Click cancel
            onNodeWithText("Cancel").performClick()

            // Then - dialog should be dismissed
            onNodeWithText("Delete Asset?").assertDoesNotExist()
        }

    @Test
    fun assetsScreen_displaysMultipleAssets() =
        runComposeUiTest {
            // Given
            val assets =
                listOf(
                    Asset(
                        id = 1L,
                        name = "Asset 1",
                    ),
                    Asset(
                        id = 2L,
                        name = "Asset 2",
                    ),
                    Asset(
                        id = 3L,
                        name = "Asset 3",
                    ),
                )
            val repository = FakeAssetRepository(assets)

            // When
            setContent {
                AssetsScreen(assetRepository = repository)
            }

            // Then - all assets should be visible
            onNodeWithText("Asset 1").assertIsDisplayed()
            onNodeWithText("Asset 2").assertIsDisplayed()
            onNodeWithText("Asset 3").assertIsDisplayed()
        }

    // Fake repository for testing
    private class FakeAssetRepository(
        private val assets: List<Asset>,
    ) : AssetRepository {
        private val assetsFlow = MutableStateFlow(assets)
        private val deletedAssets = mutableListOf<Long>()

        override fun getAllAssets(): Flow<List<Asset>> = assetsFlow

        override fun getAssetById(id: Long): Flow<Asset?> = flowOf(assets.find { it.id == id })

        override suspend fun upsertAssetByName(name: String): Long {
            val existing = assetsFlow.value.find { it.name == name }
            if (existing != null) {
                return existing.id
            }

            val newId = (assets.maxOfOrNull { it.id } ?: 0L) + 1
            val newAsset = Asset(id = newId, name = name)
            assetsFlow.value = assetsFlow.value + newAsset
            return newId
        }

        override suspend fun updateAsset(asset: Asset) {
            assetsFlow.value =
                assetsFlow.value.map {
                    if (it.id == asset.id) asset else it
                }
        }

        override suspend fun deleteAsset(id: Long) {
            deletedAssets.add(id)
            assetsFlow.value = assetsFlow.value.filter { it.id != id }
        }
    }
}

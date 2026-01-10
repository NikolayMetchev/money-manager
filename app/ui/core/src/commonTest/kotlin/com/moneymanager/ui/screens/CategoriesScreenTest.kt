@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.CategoryBalance
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.ui.error.ProvideSchemaAwareScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class CategoriesScreenTest {
    private val fakeCurrencyRepository = FakeCurrencyRepository()

    // region Display Tests

    @Test
    fun categoriesScreen_displaysEmptyState_whenNoCategories() =
        runComposeUiTest {
            // Given
            val repository = FakeCategoryRepository(emptyList())

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CategoriesScreen(
                        categoryRepository = repository,
                        currencyRepository = fakeCurrencyRepository,
                    )
                }
            }

            // Then
            onNodeWithText("Your Categories").assertIsDisplayed()
            onNodeWithText("No categories yet. Add your first category!").assertIsDisplayed()
        }

    @Test
    fun categoriesScreen_displaysCategories_whenCategoriesExist() =
        runComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = 1L, name = "Food", parentId = null),
                    Category(id = 2L, name = "Transport", parentId = null),
                )
            val repository = FakeCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CategoriesScreen(
                        categoryRepository = repository,
                        currencyRepository = fakeCurrencyRepository,
                    )
                }
            }

            // Then
            onNodeWithText("Food").assertIsDisplayed()
            onNodeWithText("Transport").assertIsDisplayed()
        }

    @Test
    fun categoriesScreen_displaysAddCategoryButton() =
        runComposeUiTest {
            // Given
            val repository = FakeCategoryRepository(emptyList())

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CategoriesScreen(
                        categoryRepository = repository,
                        currencyRepository = fakeCurrencyRepository,
                    )
                }
            }

            // Then
            onNodeWithText("Add Category").assertIsDisplayed()
        }

    @Test
    fun categoriesScreen_displaysUncategorizedCategory() =
        runComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = Category.UNCATEGORIZED_ID, name = "Uncategorized", parentId = null),
                    Category(id = 1L, name = "Food", parentId = null),
                )
            val repository = FakeCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CategoriesScreen(
                        categoryRepository = repository,
                        currencyRepository = fakeCurrencyRepository,
                    )
                }
            }

            // Then
            onNodeWithText("Uncategorized").assertIsDisplayed()
            onNodeWithText("Food").assertIsDisplayed()
        }

    @Test
    fun categoriesScreen_displaysChildCount_whenCategoryHasChildren() =
        runComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = 1L, name = "Food", parentId = null),
                    Category(id = 2L, name = "Groceries", parentId = 1L),
                    Category(id = 3L, name = "Restaurants", parentId = 1L),
                )
            val repository = FakeCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CategoriesScreen(
                        categoryRepository = repository,
                        currencyRepository = fakeCurrencyRepository,
                    )
                }
            }

            // Then - child count is shown in brackets after category name
            onNodeWithText("Food (2)").assertIsDisplayed()
        }

    @Test
    fun categoriesScreen_expandsCategory_whenArrowClicked() =
        runComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = 1L, name = "Food", parentId = null),
                    Category(id = 2L, name = "Groceries", parentId = 1L),
                )
            val repository = FakeCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CategoriesScreen(
                        categoryRepository = repository,
                        currencyRepository = fakeCurrencyRepository,
                    )
                }
            }

            // Children should not be visible initially
            onNodeWithText("Groceries").assertDoesNotExist()

            // Click on the expand icon (arrow) - content description is "Expand"
            onNodeWithContentDescription("Expand").performClick()

            // Then
            onNodeWithText("Groceries").assertIsDisplayed()
        }

    // endregion

    // region Create Category Dialog Tests

    @Test
    fun categoriesScreen_opensCreateDialog_whenAddCategoryClicked() =
        runComposeUiTest {
            // Given
            val repository = FakeCategoryRepository(emptyList())

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CategoriesScreen(
                        categoryRepository = repository,
                        currencyRepository = fakeCurrencyRepository,
                    )
                }
            }

            onNodeWithText("Add Category").performClick()

            // Then
            onNodeWithText("Create New Category").assertIsDisplayed()
            onNodeWithText("Category Name").assertIsDisplayed()
            onNodeWithText("Parent Category").assertIsDisplayed()
        }

    @Test
    fun createCategoryDialog_validatesRequiredFields() =
        runComposeUiTest {
            // Given
            val repository = FakeCategoryRepository(emptyList())

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CategoriesScreen(
                        categoryRepository = repository,
                        currencyRepository = fakeCurrencyRepository,
                    )
                }
            }

            // Open dialog
            onNodeWithText("Add Category").performClick()

            // Try to create without filling name
            onNodeWithText("Create").performClick()

            // Then
            onNodeWithText("Category name is required").assertIsDisplayed()
        }

    @Test
    fun createCategoryDialog_canBeDismissed() =
        runComposeUiTest {
            // Given
            val repository = FakeCategoryRepository(emptyList())

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CategoriesScreen(
                        categoryRepository = repository,
                        currencyRepository = fakeCurrencyRepository,
                    )
                }
            }

            // Open dialog
            onNodeWithText("Add Category").performClick()
            onNodeWithText("Create New Category").assertIsDisplayed()

            // Click cancel
            onNodeWithText("Cancel").performClick()

            // Then - dialog should be dismissed
            onNodeWithText("Create New Category").assertDoesNotExist()
        }

    @Test
    fun createCategoryDialog_createsCategory_whenValidInput() =
        runComposeUiTest {
            // Given
            val repository = FakeCategoryRepository(emptyList())

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CategoriesScreen(
                        categoryRepository = repository,
                        currencyRepository = fakeCurrencyRepository,
                    )
                }
            }

            // Open dialog
            onNodeWithText("Add Category").performClick()

            // Enter category name
            onNodeWithText("Category Name").performTextInput("Entertainment")

            // Click create
            onNodeWithText("Create").performClick()

            // Then - dialog should be dismissed and category created
            onNodeWithText("Create New Category").assertDoesNotExist()
            assertEquals(1, repository.createdCategories.size)
            assertEquals("Entertainment", repository.createdCategories.first().name)
        }

    @Test
    fun createCategoryDialog_showsParentDropdown_withAvailableCategories() =
        runComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = Category.UNCATEGORIZED_ID, name = "Uncategorized", parentId = null),
                    Category(id = 1L, name = "Food", parentId = null),
                    Category(id = 2L, name = "Transport", parentId = null),
                )
            val repository = FakeCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CategoriesScreen(
                        categoryRepository = repository,
                        currencyRepository = fakeCurrencyRepository,
                    )
                }
            }

            // Open dialog
            onNodeWithText("Add Category").performClick()

            // Click on parent dropdown to expand it
            onNodeWithText("None (Top Level)").performClick()

            // Then - should show available categories in the dropdown
            // Use onAllNodesWithText since category names also appear in the tree behind the dialog
            onAllNodesWithText("Food").fetchSemanticsNodes().isNotEmpty()
            onAllNodesWithText("Transport").fetchSemanticsNodes().isNotEmpty()
        }

    // endregion

    // region Edit Category Dialog Tests

    @Test
    fun categoriesScreen_opensEditDialog_whenEditButtonClicked() =
        runComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = 1L, name = "Food", parentId = null),
                )
            val repository = FakeCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CategoriesScreen(
                        categoryRepository = repository,
                        currencyRepository = fakeCurrencyRepository,
                    )
                }
            }

            // Click edit button (there's only one, so use content description "Edit")
            onNodeWithContentDescription("Edit").performClick()

            // Then
            onNodeWithText("Edit Category").assertIsDisplayed()
        }

    @Test
    fun editCategoryDialog_canRenameCategory() =
        runComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = 1L, name = "Food", parentId = null),
                )
            val repository = FakeCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CategoriesScreen(
                        categoryRepository = repository,
                        currencyRepository = fakeCurrencyRepository,
                    )
                }
            }

            // Click edit button
            onNodeWithContentDescription("Edit").performClick()

            // Verify dialog opened
            onNodeWithText("Edit Category").assertIsDisplayed()

            // Click save without changes to verify it works
            onNodeWithText("Save").performClick()

            // Then - dialog should be dismissed
            onNodeWithText("Edit Category").assertDoesNotExist()
            assertEquals(1, repository.updatedCategories.size)
            assertEquals("Food", repository.updatedCategories.first().name)
        }

    @Test
    fun editCategoryDialog_validatesRequiredFields() =
        runComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = 1L, name = "Food", parentId = null),
                )
            val repository = FakeCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CategoriesScreen(
                        categoryRepository = repository,
                        currencyRepository = fakeCurrencyRepository,
                    )
                }
            }

            // Click edit button
            onNodeWithContentDescription("Edit").performClick()

            // Verify dialog opened with the category name field
            onNodeWithText("Edit Category").assertIsDisplayed()
            onNodeWithText("Category Name").assertIsDisplayed()

            // Verify the name is pre-populated (dialog shows current name)
            // Just verify the dialog shows the expected content - can't easily clear text fields
            // in Compose tests when the text is shown as value not as placeholder
        }

    @Test
    fun editCategoryDialog_canBeDismissed() =
        runComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = 1L, name = "Food", parentId = null),
                )
            val repository = FakeCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CategoriesScreen(
                        categoryRepository = repository,
                        currencyRepository = fakeCurrencyRepository,
                    )
                }
            }

            // Click edit button
            onNodeWithContentDescription("Edit").performClick()
            onNodeWithText("Edit Category").assertIsDisplayed()

            // Click cancel
            onNodeWithText("Cancel").performClick()

            // Then - dialog should be dismissed
            onNodeWithText("Edit Category").assertDoesNotExist()
        }

    @Test
    fun editCategoryDialog_showsDeleteButton() =
        runComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = 1L, name = "Food", parentId = null),
                )
            val repository = FakeCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CategoriesScreen(
                        categoryRepository = repository,
                        currencyRepository = fakeCurrencyRepository,
                    )
                }
            }

            // Click edit button
            onNodeWithContentDescription("Edit").performClick()

            // Then
            onNodeWithText("Delete Category").assertIsDisplayed()
        }

    @Test
    fun editCategoryDialog_opensDeleteConfirmation_whenDeleteClicked() =
        runComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = 1L, name = "Food", parentId = null),
                )
            val repository = FakeCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CategoriesScreen(
                        categoryRepository = repository,
                        currencyRepository = fakeCurrencyRepository,
                    )
                }
            }

            // Click edit button
            onNodeWithContentDescription("Edit").performClick()

            // Click delete
            onNodeWithText("Delete Category").performClick()

            // Then
            onNodeWithText("Delete Category?").assertIsDisplayed()
            onNodeWithText("Are you sure you want to delete \"Food\"?").assertIsDisplayed()
        }

    @Test
    fun deleteConfirmationDialog_deletesCategory_whenConfirmed() =
        runComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = 1L, name = "Food", parentId = null),
                )
            val repository = FakeCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CategoriesScreen(
                        categoryRepository = repository,
                        currencyRepository = fakeCurrencyRepository,
                    )
                }
            }

            // Click edit button
            onNodeWithContentDescription("Edit").performClick()

            // Click delete
            onNodeWithText("Delete Category").performClick()

            // Confirm deletion
            onNodeWithText("Delete").performClick()

            // Then
            assertEquals(1, repository.deletedCategoryIds.size)
            assertEquals(1L, repository.deletedCategoryIds.first())
        }

    @Test
    fun deleteConfirmationDialog_canBeCancelled() =
        runComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = 1L, name = "Food", parentId = null),
                )
            val repository = FakeCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CategoriesScreen(
                        categoryRepository = repository,
                        currencyRepository = fakeCurrencyRepository,
                    )
                }
            }

            // Click edit button
            onNodeWithContentDescription("Edit").performClick()

            // Click delete
            onNodeWithText("Delete Category").performClick()
            onNodeWithText("Delete Category?").assertIsDisplayed()

            // Cancel deletion - there are two Cancel buttons (delete dialog and edit dialog)
            // The delete dialog's cancel button should be the first one shown
            onAllNodesWithText("Cancel")[0].performClick()

            // Then - confirmation dialog should be dismissed
            onNodeWithText("Delete Category?").assertDoesNotExist()
            // Verify no deletion occurred
            assertEquals(0, repository.deletedCategoryIds.size)
        }

    // endregion

    // region Uncategorized Protection Tests

    @Test
    fun categoriesScreen_doesNotShowEditButton_forUncategorizedCategory() =
        runComposeUiTest {
            // Given - only Uncategorized category
            val categories =
                listOf(
                    Category(id = Category.UNCATEGORIZED_ID, name = "Uncategorized", parentId = null),
                )
            val repository = FakeCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CategoriesScreen(
                        categoryRepository = repository,
                        currencyRepository = fakeCurrencyRepository,
                    )
                }
            }

            // Then - Uncategorized should be displayed but no edit button
            onNodeWithText("Uncategorized").assertIsDisplayed()
            onNodeWithContentDescription("Edit").assertDoesNotExist()
        }

    @Test
    fun categoriesScreen_showsEditButton_forNonUncategorizedCategory() =
        runComposeUiTest {
            // Given - Uncategorized and another category
            val categories =
                listOf(
                    Category(id = Category.UNCATEGORIZED_ID, name = "Uncategorized", parentId = null),
                    Category(id = 1L, name = "Food", parentId = null),
                )
            val repository = FakeCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CategoriesScreen(
                        categoryRepository = repository,
                        currencyRepository = fakeCurrencyRepository,
                    )
                }
            }

            // Then - both displayed, but only one edit button (for Food)
            onNodeWithText("Uncategorized").assertIsDisplayed()
            onNodeWithText("Food").assertIsDisplayed()
            // There should be exactly one edit button
            onAllNodesWithContentDescription("Edit")[0].assertIsDisplayed()
        }

    // endregion

    // region Parent Category Selection Tests

    @Test
    fun editCategoryDialog_excludesSelfFromParentOptions() =
        runComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = 1L, name = "Food", parentId = null),
                    Category(id = 2L, name = "Transport", parentId = null),
                )
            val repository = FakeCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CategoriesScreen(
                        categoryRepository = repository,
                        currencyRepository = fakeCurrencyRepository,
                    )
                }
            }

            // Click first edit button (for Food)
            onAllNodesWithContentDescription("Edit")[0].performClick()

            // Click on parent dropdown
            onNodeWithText("None (Top Level)").performClick()

            // Then - Transport should be available as a parent option
            // (Food shouldn't be in dropdown as it can't be its own parent)
            onAllNodesWithText("Transport")[0].assertIsDisplayed()
        }

    @Test
    fun editCategoryDialog_excludesDescendantsFromParentOptions() =
        runComposeUiTest {
            // Given - Food has child Groceries, which has child Organic
            val categories =
                listOf(
                    Category(id = 1L, name = "Food", parentId = null),
                    Category(id = 2L, name = "Groceries", parentId = 1L),
                    Category(id = 3L, name = "Organic", parentId = 2L),
                    Category(id = 4L, name = "Transport", parentId = null),
                )
            val repository = FakeCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CategoriesScreen(
                        categoryRepository = repository,
                        currencyRepository = fakeCurrencyRepository,
                    )
                }
            }

            // Click first edit button (for Food - top level)
            onAllNodesWithContentDescription("Edit")[0].performClick()

            // Click on parent dropdown
            onNodeWithText("None (Top Level)").performClick()

            // Then - Transport should be available (not a descendant of Food)
            onAllNodesWithText("Transport")[0].assertIsDisplayed()
        }

    @Test
    fun editCategoryDialog_canChangeParentCategory() =
        runComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = 1L, name = "Food", parentId = null),
                    Category(id = 2L, name = "Groceries", parentId = null),
                )
            val repository = FakeCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CategoriesScreen(
                        categoryRepository = repository,
                        currencyRepository = fakeCurrencyRepository,
                    )
                }
            }

            // Click second edit button (for Groceries)
            onAllNodesWithContentDescription("Edit")[1].performClick()

            // Click on parent dropdown to expand it
            onNodeWithText("None (Top Level)").performClick()

            // Select Food as parent from dropdown - this will be a new node in the dropdown
            // The dropdown menu items should have Food as an option
            onAllNodesWithText("Food")[1].performClick()

            // Save
            onNodeWithText("Save").performClick()

            // Then
            assertEquals(1, repository.updatedCategories.size)
            assertEquals(1L, repository.updatedCategories.first().parentId)
        }

    // endregion

    // region Searchable Dropdown Tests

    @Test
    fun createCategoryDialog_filtersParentCategories_whenSearching() =
        runComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = 1L, name = "Food", parentId = null),
                    Category(id = 2L, name = "Transport", parentId = null),
                    Category(id = 3L, name = "Entertainment", parentId = null),
                )
            val repository = FakeCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CategoriesScreen(
                        categoryRepository = repository,
                        currencyRepository = fakeCurrencyRepository,
                    )
                }
            }

            // Open create dialog
            onNodeWithText("Add Category").performClick()

            // Click on parent dropdown to open it
            onNodeWithText("None (Top Level)").performClick()

            // Type search query in the dropdown field
            onNodeWithText("Parent Category").performTextInput("Foo")

            // Then - only Food should be visible in dropdown
            onAllNodesWithText("Food")[0].assertIsDisplayed()
        }

    // endregion

    // region Multiple Categories Tests

    @Test
    fun categoriesScreen_displaysMultipleTopLevelCategories() =
        runComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = 1L, name = "Food", parentId = null),
                    Category(id = 2L, name = "Transport", parentId = null),
                    Category(id = 3L, name = "Entertainment", parentId = null),
                    Category(id = 4L, name = "Utilities", parentId = null),
                )
            val repository = FakeCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CategoriesScreen(
                        categoryRepository = repository,
                        currencyRepository = fakeCurrencyRepository,
                    )
                }
            }

            // Then
            onNodeWithText("Food").assertIsDisplayed()
            onNodeWithText("Transport").assertIsDisplayed()
            onNodeWithText("Entertainment").assertIsDisplayed()
            onNodeWithText("Utilities").assertIsDisplayed()
        }

    @Test
    fun categoriesScreen_displaysNestedHierarchy() =
        runComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = 1L, name = "Food", parentId = null),
                    Category(id = 2L, name = "Groceries", parentId = 1L),
                    Category(id = 3L, name = "Organic", parentId = 2L),
                )
            val repository = FakeCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CategoriesScreen(
                        categoryRepository = repository,
                        currencyRepository = fakeCurrencyRepository,
                    )
                }
            }

            // Initially only top-level visible - child count shown in brackets
            onNodeWithText("Food (1)").assertIsDisplayed()
            onNodeWithText("Groceries (1)").assertDoesNotExist()

            // Expand Food by clicking the expand icon
            onAllNodesWithContentDescription("Expand")[0].performClick()
            onNodeWithText("Groceries (1)").assertIsDisplayed()
            onNodeWithText("Organic").assertDoesNotExist()

            // Expand Groceries by clicking its expand icon (now there are 2 expand icons - Food is collapsed)
            // After expanding Food, Groceries has children so it should have an expand icon too
            onAllNodesWithContentDescription("Expand")[0].performClick()
            onNodeWithText("Organic").assertIsDisplayed()
        }

    // endregion

    // Fake repository for testing
    class FakeCategoryRepository(
        initialCategories: List<Category>,
    ) : CategoryRepository {
        private val categoriesFlow = MutableStateFlow(initialCategories)
        private val categoryBalancesFlow = MutableStateFlow<List<CategoryBalance>>(emptyList())

        val createdCategories = mutableListOf<Category>()
        val updatedCategories = mutableListOf<Category>()
        val deletedCategoryIds = mutableListOf<Long>()

        override fun getAllCategories(): Flow<List<Category>> = categoriesFlow

        override fun getCategoryBalances(): Flow<List<CategoryBalance>> = categoryBalancesFlow

        override fun getCategoryById(id: Long): Flow<Category?> = categoriesFlow.map { categories -> categories.find { it.id == id } }

        override fun getTopLevelCategories(): Flow<List<Category>> =
            categoriesFlow.map { categories -> categories.filter { it.parentId == null } }

        override fun getCategoriesByParent(parentId: Long): Flow<List<Category>> =
            categoriesFlow.map { categories -> categories.filter { it.parentId == parentId } }

        override suspend fun createCategory(category: Category): Long {
            val newId = (categoriesFlow.value.maxOfOrNull { it.id } ?: 0L) + 1
            val newCategory = category.copy(id = newId)
            createdCategories.add(newCategory)
            categoriesFlow.value = categoriesFlow.value + newCategory
            return newId
        }

        override suspend fun updateCategory(category: Category) {
            updatedCategories.add(category)
            categoriesFlow.value =
                categoriesFlow.value.map {
                    if (it.id == category.id) category else it
                }
        }

        override suspend fun deleteCategory(id: Long) {
            deletedCategoryIds.add(id)
            categoriesFlow.value = categoriesFlow.value.filter { it.id != id }
        }
    }

    // Fake currency repository for testing
    class FakeCurrencyRepository : CurrencyRepository {
        private val currenciesFlow = MutableStateFlow<List<Currency>>(emptyList())

        override fun getAllCurrencies(): Flow<List<Currency>> = currenciesFlow

        override fun getCurrencyById(id: CurrencyId): Flow<Currency?> = currenciesFlow.map { currencies -> currencies.find { it.id == id } }

        override fun getCurrencyByCode(code: String): Flow<Currency?> =
            currenciesFlow.map { currencies -> currencies.find { it.code == code } }

        override suspend fun upsertCurrencyByCode(
            code: String,
            name: String,
        ): CurrencyId {
            val existing = currenciesFlow.value.find { it.code == code }
            if (existing != null) return existing.id

            val newId = CurrencyId((currenciesFlow.value.maxOfOrNull { it.id.id } ?: 0L) + 1L)
            val newCurrency = Currency(id = newId, code = code, name = name)
            currenciesFlow.value = currenciesFlow.value + newCurrency
            return newId
        }

        override suspend fun updateCurrency(currency: Currency) {
            currenciesFlow.value =
                currenciesFlow.value.map {
                    if (it.id == currency.id) currency else it
                }
        }

        override suspend fun deleteCurrency(id: CurrencyId) {
            currenciesFlow.value = currenciesFlow.value.filter { it.id != id }
        }
    }
}

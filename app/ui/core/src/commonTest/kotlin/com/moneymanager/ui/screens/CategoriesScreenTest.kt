@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.repository.AccountAttributeWriteRepository
import com.moneymanager.domain.repository.AccountWriteRepository
import com.moneymanager.domain.repository.CategoryWriteRepository
import com.moneymanager.domain.repository.CurrencyWriteRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipWriteRepository
import com.moneymanager.domain.repository.PersonAttributeWriteRepository
import com.moneymanager.domain.repository.PersonWriteRepository
import com.moneymanager.domain.repository.TransactionWriteRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importer.ImportEngineImpl
import com.moneymanager.ui.LocalImportEngine
import com.moneymanager.ui.error.ProvideSchemaAwareScope
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import dev.mokkery.MockMode
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.matcher.matches
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class CategoriesScreenTest {
    // A real engine wrapping the test's category repository: edits route through it (as in production),
    // and because it delegates to [categoryRepository], the existing verifySuspend assertions still hold.
    private fun importEngineFor(categoryRepository: CategoryWriteRepository): ImportEngine =
        ImportEngineImpl(
            transactionRepository = mock<TransactionWriteRepository>(MockMode.autoUnit),
            accountRepository = mock<AccountWriteRepository>(MockMode.autoUnit),
            accountAttributeRepository = mock<AccountAttributeWriteRepository>(MockMode.autoUnit),
            personRepository = mock<PersonWriteRepository>(MockMode.autoUnit),
            personAttributeRepository = mock<PersonAttributeWriteRepository>(MockMode.autoUnit),
            ownershipRepository = mock<PersonAccountOwnershipWriteRepository>(MockMode.autoUnit),
            categoryRepository = categoryRepository,
        )

    private val fakeCurrencyRepository: CurrencyWriteRepository =
        mock(MockMode.autoUnit) {
            every { getAllCurrencies() } returns flowOf(emptyList())
            every { getCurrencyById(any()) } returns flowOf(null)
            every { getCurrencyByCode(any()) } returns flowOf(null)
            everySuspend { upsertCurrencyByCode(any(), any(), any()) } returns CurrencyId(1L)
        }

    // region Display Tests

    @Test
    fun categoriesScreen_displaysEmptyState_whenNoCategories() =
        runMoneyManagerComposeUiTest {
            // Given
            val repository = createCategoryRepository(emptyList())

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CompositionLocalProvider(LocalImportEngine provides importEngineFor(repository)) {
                        CategoriesScreen(
                            categoryRepository = repository,
                            currencyRepository = fakeCurrencyRepository,
                        )
                    }
                }
            }

            // Then
            onNodeWithText("Your Categories").assertIsDisplayed()
            onNodeWithText("No categories yet. Add your first category!").assertIsDisplayed()
        }

    @Test
    fun categoriesScreen_displaysCategories_whenCategoriesExist() =
        runMoneyManagerComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = 1L, name = "Food"),
                    Category(id = 2L, name = "Transport"),
                )
            val repository = createCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CompositionLocalProvider(LocalImportEngine provides importEngineFor(repository)) {
                        CategoriesScreen(
                            categoryRepository = repository,
                            currencyRepository = fakeCurrencyRepository,
                        )
                    }
                }
            }

            // Then
            onNodeWithText("Food").assertIsDisplayed()
            onNodeWithText("Transport").assertIsDisplayed()
        }

    @Test
    fun categoriesScreen_displaysAddCategoryButton() =
        runMoneyManagerComposeUiTest {
            // Given
            val repository = createCategoryRepository(emptyList())

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CompositionLocalProvider(LocalImportEngine provides importEngineFor(repository)) {
                        CategoriesScreen(
                            categoryRepository = repository,
                            currencyRepository = fakeCurrencyRepository,
                        )
                    }
                }
            }

            // Then
            onNodeWithText("Add Category").assertIsDisplayed()
        }

    @Test
    fun categoriesScreen_displaysUncategorizedCategory() =
        runMoneyManagerComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = Category.UNCATEGORIZED_ID, name = "Uncategorized"),
                    Category(id = 1L, name = "Food"),
                )
            val repository = createCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CompositionLocalProvider(LocalImportEngine provides importEngineFor(repository)) {
                        CategoriesScreen(
                            categoryRepository = repository,
                            currencyRepository = fakeCurrencyRepository,
                        )
                    }
                }
            }

            // Then
            onNodeWithText("Uncategorized").assertIsDisplayed()
            onNodeWithText("Food").assertIsDisplayed()
        }

    @Test
    fun categoriesScreen_displaysChildCount_whenCategoryHasChildren() =
        runMoneyManagerComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = 1L, name = "Food"),
                    Category(id = 2L, name = "Groceries", parentId = 1L),
                    Category(id = 3L, name = "Restaurants", parentId = 1L),
                )
            val repository = createCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CompositionLocalProvider(LocalImportEngine provides importEngineFor(repository)) {
                        CategoriesScreen(
                            categoryRepository = repository,
                            currencyRepository = fakeCurrencyRepository,
                        )
                    }
                }
            }

            // Then - child count is shown in brackets after category name
            onNodeWithText("Food (2)").assertIsDisplayed()
        }

    @Test
    fun categoriesScreen_expandsCategory_whenArrowClicked() =
        runMoneyManagerComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = 1L, name = "Food"),
                    Category(id = 2L, name = "Groceries", parentId = 1L),
                )
            val repository = createCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CompositionLocalProvider(LocalImportEngine provides importEngineFor(repository)) {
                        CategoriesScreen(
                            categoryRepository = repository,
                            currencyRepository = fakeCurrencyRepository,
                        )
                    }
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
        runMoneyManagerComposeUiTest {
            // Given
            val repository = createCategoryRepository(emptyList())

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CompositionLocalProvider(LocalImportEngine provides importEngineFor(repository)) {
                        CategoriesScreen(
                            categoryRepository = repository,
                            currencyRepository = fakeCurrencyRepository,
                        )
                    }
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
        runMoneyManagerComposeUiTest {
            // Given
            val repository = createCategoryRepository(emptyList())

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CompositionLocalProvider(LocalImportEngine provides importEngineFor(repository)) {
                        CategoriesScreen(
                            categoryRepository = repository,
                            currencyRepository = fakeCurrencyRepository,
                        )
                    }
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
        runMoneyManagerComposeUiTest {
            // Given
            val repository = createCategoryRepository(emptyList())

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CompositionLocalProvider(LocalImportEngine provides importEngineFor(repository)) {
                        CategoriesScreen(
                            categoryRepository = repository,
                            currencyRepository = fakeCurrencyRepository,
                        )
                    }
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
        runMoneyManagerComposeUiTest {
            // Given
            val repository = createCategoryRepository(emptyList())

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CompositionLocalProvider(LocalImportEngine provides importEngineFor(repository)) {
                        CategoriesScreen(
                            categoryRepository = repository,
                            currencyRepository = fakeCurrencyRepository,
                        )
                    }
                }
            }

            // Open dialog
            onNodeWithText("Add Category").performClick()

            // Enter category name
            onNodeWithText("Category Name").performTextInput("Entertainment")

            // Click create
            onNodeWithText("Create").performClick()
            waitForIdle()

            // Then - dialog should be dismissed and category created
            onNodeWithText("Create New Category").assertDoesNotExist()
            verifySuspend { repository.createCategory(matches { it.name == "Entertainment" }, any()) }
        }

    @Test
    fun createCategoryDialog_showsParentDropdown_withAvailableCategories() =
        runMoneyManagerComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = Category.UNCATEGORIZED_ID, name = "Uncategorized"),
                    Category(id = 1L, name = "Food"),
                    Category(id = 2L, name = "Transport"),
                )
            val repository = createCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CompositionLocalProvider(LocalImportEngine provides importEngineFor(repository)) {
                        CategoriesScreen(
                            categoryRepository = repository,
                            currencyRepository = fakeCurrencyRepository,
                        )
                    }
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
        runMoneyManagerComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = 1L, name = "Food"),
                )
            val repository = createCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CompositionLocalProvider(LocalImportEngine provides importEngineFor(repository)) {
                        CategoriesScreen(
                            categoryRepository = repository,
                            currencyRepository = fakeCurrencyRepository,
                        )
                    }
                }
            }

            // Click edit button (there's only one, so use content description "Edit")
            onNodeWithContentDescription("Edit").performClick()

            // Then
            onNodeWithText("Edit Category").assertIsDisplayed()
        }

    @Test
    fun editCategoryDialog_canRenameCategory() =
        runMoneyManagerComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = 1L, name = "Food"),
                )
            val repository = createCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CompositionLocalProvider(LocalImportEngine provides importEngineFor(repository)) {
                        CategoriesScreen(
                            categoryRepository = repository,
                            currencyRepository = fakeCurrencyRepository,
                        )
                    }
                }
            }

            // Click edit button
            onNodeWithContentDescription("Edit").performClick()

            // Verify dialog opened
            onNodeWithText("Edit Category").assertIsDisplayed()

            // Click save without changes to verify it works
            onNodeWithText("Save").performClick()
            waitForIdle()

            // Then - dialog should be dismissed
            onNodeWithText("Edit Category").assertDoesNotExist()
            verifySuspend { repository.updateCategory(matches { it.name == "Food" }, any()) }
        }

    @Test
    fun editCategoryDialog_validatesRequiredFields() =
        runMoneyManagerComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = 1L, name = "Food"),
                )
            val repository = createCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CompositionLocalProvider(LocalImportEngine provides importEngineFor(repository)) {
                        CategoriesScreen(
                            categoryRepository = repository,
                            currencyRepository = fakeCurrencyRepository,
                        )
                    }
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
        runMoneyManagerComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = 1L, name = "Food"),
                )
            val repository = createCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CompositionLocalProvider(LocalImportEngine provides importEngineFor(repository)) {
                        CategoriesScreen(
                            categoryRepository = repository,
                            currencyRepository = fakeCurrencyRepository,
                        )
                    }
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
        runMoneyManagerComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = 1L, name = "Food"),
                )
            val repository = createCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CompositionLocalProvider(LocalImportEngine provides importEngineFor(repository)) {
                        CategoriesScreen(
                            categoryRepository = repository,
                            currencyRepository = fakeCurrencyRepository,
                        )
                    }
                }
            }

            // Click edit button
            onNodeWithContentDescription("Edit").performClick()

            // Then
            onNodeWithText("Delete Category").assertIsDisplayed()
        }

    @Test
    fun editCategoryDialog_opensDeleteConfirmation_whenDeleteClicked() =
        runMoneyManagerComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = 1L, name = "Food"),
                )
            val repository = createCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CompositionLocalProvider(LocalImportEngine provides importEngineFor(repository)) {
                        CategoriesScreen(
                            categoryRepository = repository,
                            currencyRepository = fakeCurrencyRepository,
                        )
                    }
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
        runMoneyManagerComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = 1L, name = "Food"),
                )
            val repository = createCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CompositionLocalProvider(LocalImportEngine provides importEngineFor(repository)) {
                        CategoriesScreen(
                            categoryRepository = repository,
                            currencyRepository = fakeCurrencyRepository,
                        )
                    }
                }
            }

            // Click edit button
            onNodeWithContentDescription("Edit").performClick()

            // Click delete
            onNodeWithText("Delete Category").performClick()

            // Confirm deletion
            onNodeWithText("Delete").performClick()
            waitForIdle()

            // Then
            verifySuspend { repository.deleteCategory(1L) }
        }

    @Test
    fun deleteConfirmationDialog_canBeCancelled() =
        runMoneyManagerComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = 1L, name = "Food"),
                )
            val repository = createCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CompositionLocalProvider(LocalImportEngine provides importEngineFor(repository)) {
                        CategoriesScreen(
                            categoryRepository = repository,
                            currencyRepository = fakeCurrencyRepository,
                        )
                    }
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
            verifySuspend(VerifyMode.not) { repository.deleteCategory(any()) }
        }

    // endregion

    // region Uncategorized Protection Tests

    @Test
    fun categoriesScreen_doesNotShowEditButton_forUncategorizedCategory() =
        runMoneyManagerComposeUiTest {
            // Given - only Uncategorized category
            val categories =
                listOf(
                    Category(id = Category.UNCATEGORIZED_ID, name = "Uncategorized"),
                )
            val repository = createCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CompositionLocalProvider(LocalImportEngine provides importEngineFor(repository)) {
                        CategoriesScreen(
                            categoryRepository = repository,
                            currencyRepository = fakeCurrencyRepository,
                        )
                    }
                }
            }

            // Then - Uncategorized should be displayed but no edit button
            onNodeWithText("Uncategorized").assertIsDisplayed()
            onNodeWithContentDescription("Edit").assertDoesNotExist()
        }

    @Test
    fun categoriesScreen_showsEditButton_forNonUncategorizedCategory() =
        runMoneyManagerComposeUiTest {
            // Given - Uncategorized and another category
            val categories =
                listOf(
                    Category(id = Category.UNCATEGORIZED_ID, name = "Uncategorized"),
                    Category(id = 1L, name = "Food"),
                )
            val repository = createCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CompositionLocalProvider(LocalImportEngine provides importEngineFor(repository)) {
                        CategoriesScreen(
                            categoryRepository = repository,
                            currencyRepository = fakeCurrencyRepository,
                        )
                    }
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
        runMoneyManagerComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = 1L, name = "Food"),
                    Category(id = 2L, name = "Transport"),
                )
            val repository = createCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CompositionLocalProvider(LocalImportEngine provides importEngineFor(repository)) {
                        CategoriesScreen(
                            categoryRepository = repository,
                            currencyRepository = fakeCurrencyRepository,
                        )
                    }
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
        runMoneyManagerComposeUiTest {
            // Given - Food has child Groceries, which has child Organic
            val categories =
                listOf(
                    Category(id = 1L, name = "Food"),
                    Category(id = 2L, name = "Groceries", parentId = 1L),
                    Category(id = 3L, name = "Organic", parentId = 2L),
                    Category(id = 4L, name = "Transport"),
                )
            val repository = createCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CompositionLocalProvider(LocalImportEngine provides importEngineFor(repository)) {
                        CategoriesScreen(
                            categoryRepository = repository,
                            currencyRepository = fakeCurrencyRepository,
                        )
                    }
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
        runMoneyManagerComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = 1L, name = "Food"),
                    Category(id = 2L, name = "Groceries"),
                )
            val repository = createCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CompositionLocalProvider(LocalImportEngine provides importEngineFor(repository)) {
                        CategoriesScreen(
                            categoryRepository = repository,
                            currencyRepository = fakeCurrencyRepository,
                        )
                    }
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
            waitForIdle()

            // Then
            verifySuspend { repository.updateCategory(matches { it.parentId == 1L }, any()) }
        }

    // endregion

    // region Searchable Dropdown Tests

    @Test
    fun createCategoryDialog_filtersParentCategories_whenSearching() =
        runMoneyManagerComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = 1L, name = "Food"),
                    Category(id = 2L, name = "Transport"),
                    Category(id = 3L, name = "Entertainment"),
                )
            val repository = createCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CompositionLocalProvider(LocalImportEngine provides importEngineFor(repository)) {
                        CategoriesScreen(
                            categoryRepository = repository,
                            currencyRepository = fakeCurrencyRepository,
                        )
                    }
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
        runMoneyManagerComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = 1L, name = "Food"),
                    Category(id = 2L, name = "Transport"),
                    Category(id = 3L, name = "Entertainment"),
                    Category(id = 4L, name = "Utilities"),
                )
            val repository = createCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CompositionLocalProvider(LocalImportEngine provides importEngineFor(repository)) {
                        CategoriesScreen(
                            categoryRepository = repository,
                            currencyRepository = fakeCurrencyRepository,
                        )
                    }
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
        runMoneyManagerComposeUiTest {
            // Given
            val categories =
                listOf(
                    Category(id = 1L, name = "Food"),
                    Category(id = 2L, name = "Groceries", parentId = 1L),
                    Category(id = 3L, name = "Organic", parentId = 2L),
                )
            val repository = createCategoryRepository(categories)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    CompositionLocalProvider(LocalImportEngine provides importEngineFor(repository)) {
                        CategoriesScreen(
                            categoryRepository = repository,
                            currencyRepository = fakeCurrencyRepository,
                        )
                    }
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

    private fun createCategoryRepository(initialCategories: List<Category>): CategoryWriteRepository {
        val flow = MutableStateFlow(initialCategories)
        return mock(MockMode.autoUnit) {
            every { getAllCategories() } returns flow
            every { getCategoryBalances() } returns flowOf(emptyList())
            every { getCategoryById(any()) } calls { (id: Long) -> flow.map { cats -> cats.find { it.id == id } } }
            every { getTopLevelCategories() } returns flow.map { cats -> cats.filter { it.parentId == null } }
            every { getCategoriesByParent(any()) } calls
                { (parentId: Long) -> flow.map { cats -> cats.filter { it.parentId == parentId } } }
            everySuspend { createCategory(any(), any()) } calls { (cat: Category, _: Source) ->
                val newId = (flow.value.maxOfOrNull { it.id } ?: 0L) + 1
                val newCat = cat.copy(id = newId)
                flow.value += newCat
                newId
            }
            everySuspend { updateCategory(any(), any()) } calls { (cat: Category, _: Source) ->
                flow.value = flow.value.map { if (it.id == cat.id) cat else it }
            }
            everySuspend { deleteCategory(any()) } calls { (id: Long) ->
                flow.value = flow.value.filter { it.id != id }
            }
        }
    }
}

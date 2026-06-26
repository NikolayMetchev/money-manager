@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.moneymanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportCategoryIntent
import com.moneymanager.importengineapi.LocalCategoryKey
import com.moneymanager.ui.LocalImportEngine
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

private val logger = logging()

@Composable
fun CreateCategoryDialog(
    categoryRepository: CategoryReadRepository,
    onCategoryCreated: (id: Long, name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedParentId by remember { mutableStateOf<Long?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    val source = Source.Manual
    val importEngine = LocalImportEngine.current

    val categories by categoryRepository
        .getAllCategories()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val scope = rememberSchemaAwareCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("Create New Category") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Category Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isSaving,
                )

                ParentCategorySelector(
                    categories = categories,
                    selectedParentId = selectedParentId,
                    onParentSelected = { selectedParentId = it },
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    enabled = !isSaving,
                    additionalExcludedIds = emptySet(),
                )

                errorMessage?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isBlank()) {
                        errorMessage = "Category name is required"
                    } else {
                        isSaving = true
                        errorMessage = null
                        scope.launch {
                            try {
                                val key = LocalCategoryKey("create")
                                val result =
                                    importEngine.import(
                                        ImportBatch.manualEdits(
                                            categories =
                                                listOf(
                                                    ImportCategoryIntent(
                                                        key = key,
                                                        source = source,
                                                        name = name.trim(),
                                                        parentId = selectedParentId,
                                                    ),
                                                ),
                                        ),
                                    )
                                onCategoryCreated(result.createdCategoryIds.getValue(key), name.trim())
                            } catch (expected: Exception) {
                                logger.error(expected) { "Failed to create category: ${expected.message}" }
                                errorMessage = "Failed to create category: ${expected.message}"
                                isSaving = false
                            }
                        }
                    }
                },
                enabled = !isSaving,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Create")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSaving,
            ) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun ParentCategorySelector(
    categories: List<Category>,
    selectedParentId: Long?,
    onParentSelected: (Long?) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    enabled: Boolean,
    additionalExcludedIds: Set<Long>,
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredCategories =
        remember(categories, searchQuery, additionalExcludedIds) {
            val available =
                categories.filter {
                    it.id != Category.UNCATEGORIZED_ID && it.id !in additionalExcludedIds
                }
            if (searchQuery.isBlank()) available else available.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { onExpandedChange(!expanded && enabled) },
    ) {
        OutlinedTextField(
            value =
                if (expanded) {
                    searchQuery
                } else if (selectedParentId == null) {
                    "None (Top Level)"
                } else {
                    categories.find { it.id == selectedParentId }?.name ?: "None (Top Level)"
                },
            onValueChange = { searchQuery = it },
            label = { Text("Parent Category") },
            placeholder = { Text("Type to search...") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
            enabled = enabled,
            singleLine = true,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                onExpandedChange(false)
                searchQuery = ""
            },
        ) {
            DropdownMenuItem(
                text = { Text("None (Top Level)") },
                onClick = {
                    onParentSelected(null)
                    onExpandedChange(false)
                    searchQuery = ""
                },
            )
            filteredCategories.forEach { category ->
                DropdownMenuItem(
                    text = { Text(category.name) },
                    onClick = {
                        onParentSelected(category.id)
                        onExpandedChange(false)
                        searchQuery = ""
                    },
                )
            }
        }
    }
}

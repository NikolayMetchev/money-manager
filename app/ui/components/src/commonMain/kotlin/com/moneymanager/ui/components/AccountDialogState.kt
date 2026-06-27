package com.moneymanager.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

internal class AccountDialogState(
    initialName: String,
    initialCategoryId: Long,
) {
    private var nameBacking by mutableStateOf(initialName)

    // Editing the name clears its error highlight so the red outline disappears as soon as the user
    // starts filling in the required field.
    var name: String
        get() = nameBacking
        set(value) {
            nameBacking = value
            nameError = false
        }
    var nameError by mutableStateOf(false)
    var selectedCategoryId by mutableStateOf(initialCategoryId)
    var selectedCategoryName by mutableStateOf<String?>(null)
    var categoryExpanded by mutableStateOf(false)
    var showCreateCategoryDialog by mutableStateOf(false)
    var showCreatePersonDialog by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var isSaving by mutableStateOf(false)

    fun selectCategory(categoryId: Long) {
        selectedCategoryId = categoryId
        selectedCategoryName = null
    }

    fun selectCreatedCategory(
        categoryId: Long,
        categoryName: String,
    ) {
        selectedCategoryId = categoryId
        selectedCategoryName = categoryName
        showCreateCategoryDialog = false
    }
}

@Composable
internal fun rememberAccountDialogState(
    initialName: String,
    initialCategoryId: Long,
): AccountDialogState = remember(initialName, initialCategoryId) { AccountDialogState(initialName, initialCategoryId) }

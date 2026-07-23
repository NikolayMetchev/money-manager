@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.Category
import com.moneymanager.ui.util.onEnterKeyDown

@Composable
internal fun AccountDialogContent(
    accountState: AccountDialogState,
    categories: List<Category>,
    modifier: Modifier = Modifier,
    nameFocusRequester: FocusRequester? = null,
    onNameSubmit: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AccountBasicsFields(
            name = accountState.name,
            onNameChange = { accountState.name = it },
            categories = categories,
            selectedCategoryId = accountState.selectedCategoryId,
            selectedCategoryName = accountState.selectedCategoryName,
            expanded = accountState.categoryExpanded,
            isSaving = accountState.isSaving,
            onExpandedChange = { accountState.categoryExpanded = it },
            onCategorySelected = accountState::selectCategory,
            onCreateCategoryClick = { accountState.showCreateCategoryDialog = true },
            nameFocusRequester = nameFocusRequester,
            nameIsError = accountState.nameError,
            onNameSubmit = onNameSubmit,
        )

        content()
    }
}

@Composable
internal fun AccountBasicsFields(
    name: String,
    onNameChange: (String) -> Unit,
    categories: List<Category>,
    selectedCategoryId: Long,
    selectedCategoryName: String?,
    expanded: Boolean,
    isSaving: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onCategorySelected: (Long) -> Unit,
    onCreateCategoryClick: () -> Unit,
    nameFocusRequester: FocusRequester? = null,
    nameIsError: Boolean = false,
    onNameSubmit: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text("Account Name") },
        modifier =
            Modifier
                .fillMaxWidth()
                .let { if (nameFocusRequester != null) it.focusRequester(nameFocusRequester) else it }
                .let { if (onNameSubmit != null) it.onEnterKeyDown(onNameSubmit) else it },
        singleLine = true,
        enabled = !isSaving,
        isError = nameIsError,
    )

    AccountCategorySelector(
        categories = categories,
        selectedCategoryId = selectedCategoryId,
        selectedCategoryName = selectedCategoryName,
        expanded = expanded,
        isSaving = isSaving,
        onExpandedChange = onExpandedChange,
        onCategorySelected = onCategorySelected,
        onCreateCategoryClick = onCreateCategoryClick,
    )
}

@Composable
internal fun AccountOwnersSection(
    hasPeople: Boolean,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Owners",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (!hasPeople) {
            Text(
                text = "No people available. Create one first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        content()
    }
}

@Composable
internal fun AccountCategorySelector(
    categories: List<Category>,
    selectedCategoryId: Long,
    selectedCategoryName: String?,
    expanded: Boolean,
    isSaving: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onCategorySelected: (Long) -> Unit,
    onCreateCategoryClick: () -> Unit,
) {
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { onExpandedChange(!expanded && !isSaving) },
    ) {
        OutlinedTextField(
            value =
                selectedCategoryName
                    ?: categories.find { it.id == selectedCategoryId }?.name
                    ?: "Uncategorized",
            onValueChange = {},
            readOnly = true,
            label = { Text("Category") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            enabled = !isSaving,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            categories.forEach { category ->
                DropdownMenuItem(
                    text = { Text(category.name) },
                    onClick = {
                        onCategorySelected(category.id)
                        onExpandedChange(false)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("+ Create New Category") },
                onClick = {
                    onExpandedChange(false)
                    onCreateCategoryClick()
                },
            )
        }
    }
}

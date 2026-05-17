@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.components

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
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.Category

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
) {
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text("Account Name") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = !isSaving,
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

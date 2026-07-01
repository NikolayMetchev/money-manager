@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.CsvReferenceType
import com.moneymanager.domain.CsvResolution
import com.moneymanager.domain.CsvUnresolvedReference
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.PersonReadRepository

/**
 * Row for resolving a single unresolved account/category/currency reference to an existing entity or a
 * new one. Shared by CSV strategy file import and the remote strategy-library pull flow.
 */
@Composable
fun ReferenceResolutionRow(
    reference: CsvUnresolvedReference,
    resolution: CsvResolution?,
    onResolutionChanged: (CsvResolution) -> Unit,
    accounts: List<Account>,
    categories: List<Category>,
    currencies: List<Currency>,
    categoryRepository: CategoryReadRepository,
    personRepository: PersonReadRepository,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    var createNewName by remember { mutableStateOf(reference.name) }
    var showCreateAccountDialog by remember { mutableStateOf(false) }
    var selectedOption by remember(resolution) {
        mutableStateOf(
            when (resolution) {
                is CsvResolution.CreateNew -> "create"
                is CsvResolution.MapToExisting -> "existing:${resolution.id}"
                is CsvResolution.MapToExistingCurrency -> "existing:${resolution.id}"
                null -> null
            },
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = reference.name,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    val refType =
                        reference.type.name
                            .lowercase()
                            .replaceFirstChar { it.uppercase() }
                    val fieldName =
                        reference.fieldType
                            ?.name
                            ?.lowercase()
                            ?.replace("_", " ")
                            ?: "account mapping"
                    Text(
                        text = "$refType for $fieldName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (resolution != null) {
                    Text(
                        "✓",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            when (reference.type) {
                CsvReferenceType.ACCOUNT -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { if (enabled) expanded = !expanded },
                            modifier = Modifier.weight(1f),
                        ) {
                            OutlinedTextField(
                                value =
                                    when {
                                        selectedOption?.startsWith("existing:") == true -> {
                                            val id = selectedOption!!.removePrefix("existing:").toLongOrNull()
                                            accounts.find { it.id.id == id }?.name ?: "Select..."
                                        }
                                        selectedOption == "create" -> "Create on import: $createNewName"
                                        else -> "Select..."
                                    },
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Map to") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                                enabled = enabled,
                            )
                            ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false },
                            ) {
                                accounts.forEach { account ->
                                    DropdownMenuItem(
                                        text = { Text(account.name) },
                                        onClick = {
                                            selectedOption = "existing:${account.id.id}"
                                            onResolutionChanged(CsvResolution.MapToExisting(account.id.id))
                                            expanded = false
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Create on import",
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    onClick = {
                                        selectedOption = "create"
                                        onResolutionChanged(CsvResolution.CreateNew(createNewName))
                                        expanded = false
                                    },
                                )
                            }
                        }
                        TextButton(
                            onClick = { showCreateAccountDialog = true },
                            enabled = enabled,
                        ) {
                            Text("Create Account")
                        }
                    }

                    if (selectedOption == "create") {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = createNewName,
                            onValueChange = { newName ->
                                createNewName = newName
                                onResolutionChanged(CsvResolution.CreateNew(newName))
                            },
                            label = { Text("New account name") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = enabled,
                        )
                    }

                    if (showCreateAccountDialog) {
                        CreateAccountDialog(
                            categoryRepository = categoryRepository,
                            personRepository = personRepository,
                            initialName = reference.name,
                            onDismiss = { showCreateAccountDialog = false },
                            onAccountCreated = { accountId ->
                                selectedOption = "existing:${accountId.id}"
                                onResolutionChanged(CsvResolution.MapToExisting(accountId.id))
                            },
                        )
                    }
                }

                CsvReferenceType.CATEGORY -> {
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { if (enabled) expanded = !expanded },
                    ) {
                        OutlinedTextField(
                            value =
                                when {
                                    selectedOption == "create" -> "Create: $createNewName"
                                    selectedOption?.startsWith("existing:") == true -> {
                                        val id = selectedOption!!.removePrefix("existing:").toLongOrNull()
                                        categories.find { it.id == id }?.name ?: "Select..."
                                    }
                                    else -> "Select..."
                                },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Map to") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                            enabled = enabled,
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Create new category",
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                onClick = {
                                    selectedOption = "create"
                                    onResolutionChanged(CsvResolution.CreateNew(createNewName))
                                    expanded = false
                                },
                            )
                            categories.forEach { category ->
                                DropdownMenuItem(
                                    text = { Text(category.name) },
                                    onClick = {
                                        selectedOption = "existing:${category.id}"
                                        onResolutionChanged(CsvResolution.MapToExisting(category.id))
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }

                    if (selectedOption == "create") {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = createNewName,
                            onValueChange = { newName ->
                                createNewName = newName
                                onResolutionChanged(CsvResolution.CreateNew(newName))
                            },
                            label = { Text("New category name") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = enabled,
                        )
                    }
                }

                CsvReferenceType.CURRENCY -> {
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { if (enabled) expanded = !expanded },
                    ) {
                        OutlinedTextField(
                            value =
                                when {
                                    selectedOption == "create" -> "Create: $createNewName"
                                    selectedOption?.startsWith("existing:") == true -> {
                                        val id = selectedOption!!.removePrefix("existing:")
                                        currencies.find { it.id.id.toString() == id }?.let { "${it.code} - ${it.name}" }
                                            ?: "Select..."
                                    }
                                    else -> "Select..."
                                },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Map to") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                            enabled = enabled,
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Create new currency",
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                onClick = {
                                    selectedOption = "create"
                                    onResolutionChanged(CsvResolution.CreateNew(createNewName))
                                    expanded = false
                                },
                            )
                            currencies.forEach { currency ->
                                DropdownMenuItem(
                                    text = { Text("${currency.code} - ${currency.name}") },
                                    onClick = {
                                        selectedOption = "existing:${currency.id.id}"
                                        onResolutionChanged(CsvResolution.MapToExistingCurrency(currency.id.id.toString()))
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }

                    if (selectedOption == "create") {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = createNewName,
                            onValueChange = { newName ->
                                createNewName = newName
                                onResolutionChanged(CsvResolution.CreateNew(newName))
                            },
                            label = { Text("New currency code") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = enabled,
                        )
                    }
                }
            }
        }
    }
}

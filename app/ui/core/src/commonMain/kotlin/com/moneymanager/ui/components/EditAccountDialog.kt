@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.moneymanager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
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
import com.moneymanager.database.ManualEntitySourceRecorder
import com.moneymanager.database.sql.EntitySourceQueries
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipRepository
import com.moneymanager.domain.repository.PersonRepository
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.screens.CreateCategoryDialog
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

private val logger = logging()

/**
 * A dialog for editing an existing account with category and owner selection.
 */
@Composable
fun EditAccountDialog(
    account: Account,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    personRepository: PersonRepository,
    personAccountOwnershipRepository: PersonAccountOwnershipRepository,
    entitySourceQueries: EntitySourceQueries,
    deviceId: DeviceId,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(account.name) }
    var selectedCategoryId by remember { mutableStateOf(account.categoryId) }
    var selectedCategoryName by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var showCreateCategoryDialog by remember { mutableStateOf(false) }
    var showCreatePersonDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    val categories by categoryRepository.getAllCategories()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val people by personRepository.getAllPeople()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val existingOwnerships by personAccountOwnershipRepository.getOwnershipsByAccount(account.id)
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())

    var selectedOwnerIds by remember(existingOwnerships) {
        mutableStateOf(existingOwnerships.map { it.personId.id }.toSet())
    }

    val scope = rememberSchemaAwareCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("Edit Account") },
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
                    label = { Text("Account Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isSaving,
                )

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded && !isSaving },
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
                        onDismissRequest = { expanded = false },
                    ) {
                        categories.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category.name) },
                                onClick = {
                                    selectedCategoryId = category.id
                                    selectedCategoryName = null
                                    expanded = false
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("+ Create New Category") },
                            onClick = {
                                expanded = false
                                showCreateCategoryDialog = true
                            },
                        )
                    }
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Owners",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    if (people.isEmpty()) {
                        Text(
                            text = "No people available. Create one first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        people.forEach { person ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = selectedOwnerIds.contains(person.id.id),
                                    onCheckedChange = { checked ->
                                        selectedOwnerIds =
                                            if (checked) {
                                                selectedOwnerIds + person.id.id
                                            } else {
                                                selectedOwnerIds - person.id.id
                                            }
                                    },
                                    enabled = !isSaving,
                                )
                                Text(
                                    text = person.fullName,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                    TextButton(
                        onClick = { showCreatePersonDialog = true },
                        enabled = !isSaving,
                    ) {
                        Text("+ Add New Person")
                    }
                }

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
                        errorMessage = "Account name is required"
                    } else {
                        isSaving = true
                        errorMessage = null
                        scope.launch {
                            try {
                                val updatedAccount =
                                    account.copy(
                                        name = name.trim(),
                                        categoryId = selectedCategoryId,
                                    )
                                val newRevisionId = accountRepository.updateAccount(updatedAccount)

                                // Record source for account update audit trail
                                ManualEntitySourceRecorder(entitySourceQueries, deviceId).insert(
                                    EntityType.ACCOUNT,
                                    account.id.id,
                                    newRevisionId,
                                )

                                val existingOwnerIds = existingOwnerships.map { it.personId.id }.toSet()
                                val ownersToAdd = selectedOwnerIds - existingOwnerIds
                                val ownersToRemove = existingOwnerIds - selectedOwnerIds

                                ownersToRemove.forEach { personId ->
                                    val ownership = existingOwnerships.find { it.personId.id == personId }
                                    ownership?.let {
                                        personAccountOwnershipRepository.deleteOwnership(it.id)
                                    }
                                }

                                ownersToAdd.forEach { personId ->
                                    val ownershipId =
                                        personAccountOwnershipRepository.createOwnership(
                                            personId = PersonId(personId),
                                            accountId = account.id,
                                        )
                                    // Record source for new ownership audit trail
                                    ManualEntitySourceRecorder(entitySourceQueries, deviceId).insert(
                                        EntityType.PERSON_ACCOUNT_OWNERSHIP,
                                        ownershipId,
                                        1L,
                                    )
                                }

                                onDismiss()
                            } catch (expected: Exception) {
                                logger.error(expected) { "Failed to update account: ${expected.message}" }
                                errorMessage = "Failed to update account: ${expected.message}"
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
                    Text("Save")
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

    if (showCreateCategoryDialog) {
        CreateCategoryDialog(
            categoryRepository = categoryRepository,
            onCategoryCreated = { categoryId, categoryName ->
                selectedCategoryId = categoryId
                selectedCategoryName = categoryName
                showCreateCategoryDialog = false
            },
            onDismiss = { showCreateCategoryDialog = false },
        )
    }

    if (showCreatePersonDialog) {
        EditPersonDialog(
            personToEdit = null,
            personRepository = personRepository,
            entitySourceQueries = entitySourceQueries,
            deviceId = deviceId,
            onDismiss = { showCreatePersonDialog = false },
        )
    }
}

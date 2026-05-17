@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.moneymanager.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
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
import com.moneymanager.domain.EntitySource
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipRepository
import com.moneymanager.domain.repository.PersonAttributeRepository
import com.moneymanager.domain.repository.PersonRepository
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.screens.CreateCategoryDialog
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging
import kotlin.time.Clock

private val logger = logging()

/**
 * A dialog for creating a new account with optional category and owner selection.
 * Supports inline category and person creation via nested dialogs.
 */
@Composable
fun CreateAccountDialog(
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    personRepository: PersonRepository,
    personAttributeRepository: PersonAttributeRepository? = null,
    personAccountOwnershipRepository: PersonAccountOwnershipRepository,
    entitySource: EntitySource,
    onDismiss: () -> Unit,
    onAccountCreated: ((AccountId) -> Unit)? = null,
    initialName: String = "",
) {
    val accountState = rememberAccountDialogState(initialName = initialName, initialCategoryId = -1L)
    var selectedOwnerIds by remember { mutableStateOf(setOf<Long>()) }
    var selectedOwnerIdForAddition by remember { mutableStateOf<Long?>(null) }
    var ownerDropdownExpanded by remember { mutableStateOf(false) }

    val categories by categoryRepository
        .getAllCategories()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val people by personRepository
        .getAllPeople()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val scope = rememberSchemaAwareCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!accountState.isSaving) onDismiss() },
        title = { Text("Create New Account") },
        text = {
            AccountDialogContent(
                accountState = accountState,
                categories = categories,
            ) {
                AccountOwnersSection(hasPeople = people.isNotEmpty()) {
                    if (people.isNotEmpty()) {
                        ExposedDropdownMenuBox(
                            expanded = ownerDropdownExpanded,
                            onExpandedChange = { ownerDropdownExpanded = !ownerDropdownExpanded && !accountState.isSaving },
                        ) {
                            OutlinedTextField(
                                value = people.find { it.id.id == selectedOwnerIdForAddition }?.fullName ?: "Select owner",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Owner") },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = ownerDropdownExpanded)
                                },
                                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                                enabled = !accountState.isSaving && people.any { !selectedOwnerIds.contains(it.id.id) },
                            )
                            ExposedDropdownMenu(
                                expanded = ownerDropdownExpanded,
                                onDismissRequest = { ownerDropdownExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("+ Create New Person") },
                                    onClick = {
                                        ownerDropdownExpanded = false
                                        accountState.showCreatePersonDialog = true
                                    },
                                )
                                HorizontalDivider()
                                people
                                    .filterNot { selectedOwnerIds.contains(it.id.id) }
                                    .forEach { person ->
                                        DropdownMenuItem(
                                            text = { Text(person.fullName) },
                                            onClick = {
                                                selectedOwnerIdForAddition = person.id.id
                                                ownerDropdownExpanded = false
                                            },
                                        )
                                    }
                            }
                        }
                        TextButton(
                            onClick = {
                                selectedOwnerIdForAddition?.let { personId ->
                                    selectedOwnerIds = selectedOwnerIds + personId
                                    selectedOwnerIdForAddition = null
                                }
                            },
                            enabled = !accountState.isSaving && selectedOwnerIdForAddition != null,
                        ) {
                            Text("+ Add Owner")
                        }

                        selectedOwnerIds.forEach { selectedOwnerId ->
                            val person = people.find { it.id.id == selectedOwnerId } ?: return@forEach
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = person.fullName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = {
                                        selectedOwnerIds = selectedOwnerIds - person.id.id
                                    },
                                    enabled = !accountState.isSaving,
                                ) {
                                    Text("Remove")
                                }
                            }
                        }
                    }
                }

                accountState.errorMessage?.let { error -> ErrorMessageText(error) }
            }
        },
        confirmButton = {
            LoadingTextButton(
                onClick = {
                    if (accountState.name.isBlank()) {
                        accountState.errorMessage = "Account name is required"
                    } else {
                        accountState.isSaving = true
                        accountState.errorMessage = null
                        scope.launch {
                            try {
                                val now = Clock.System.now()
                                val newAccount =
                                    Account(
                                        id = AccountId(0),
                                        name = accountState.name.trim(),
                                        openingDate = now,
                                        categoryId = accountState.selectedCategoryId,
                                    )
                                val accountId = accountRepository.createAccount(newAccount)
                                // Record source for audit trail
                                entitySource.record(EntityType.ACCOUNT, accountId.id, 1L)
                                selectedOwnerIds.forEach { personId ->
                                    val ownershipId =
                                        personAccountOwnershipRepository.createOwnership(
                                            personId = PersonId(personId),
                                            accountId = accountId,
                                        )
                                    // Record source for ownership audit trail
                                    entitySource.record(EntityType.PERSON_ACCOUNT_OWNERSHIP, ownershipId, 1L)
                                }
                                onAccountCreated?.invoke(accountId)
                                onDismiss()
                            } catch (expected: Exception) {
                                logger.error(expected) { "Failed to create account: ${expected.message}" }
                                accountState.errorMessage = "Failed to create account: ${expected.message}"
                                accountState.isSaving = false
                            }
                        }
                    }
                },
                enabled = !accountState.isSaving,
                loading = accountState.isSaving,
                label = "Create",
            )
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !accountState.isSaving,
            ) {
                Text("Cancel")
            }
        },
    )

    if (accountState.showCreateCategoryDialog) {
        CreateCategoryDialog(
            categoryRepository = categoryRepository,
            onCategoryCreated = accountState::selectCreatedCategory,
            onDismiss = { accountState.showCreateCategoryDialog = false },
        )
    }

    if (accountState.showCreatePersonDialog) {
        EditPersonDialog(
            personToEdit = null,
            personRepository = personRepository,
            personAttributeRepository = personAttributeRepository,
            entitySource = entitySource,
            onPersonCreated = { personId ->
                selectedOwnerIdForAddition = personId.id
            },
            onDismiss = { accountState.showCreatePersonDialog = false },
        )
    }
}

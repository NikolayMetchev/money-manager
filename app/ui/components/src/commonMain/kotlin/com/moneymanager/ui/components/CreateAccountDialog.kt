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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.repository.AttributeTypeReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.PersonAttributeReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.ImportAccountIntent
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportOwnershipIntent
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.ui.components.CreateCategoryDialog
import com.moneymanager.ui.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.foundation.LocalImportEngine
import com.moneymanager.ui.util.onEnterKeyDown
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
    categoryRepository: CategoryReadRepository,
    personRepository: PersonReadRepository,
    personAttributeRepository: PersonAttributeReadRepository? = null,
    attributeTypeRepository: AttributeTypeReadRepository? = null,
    onDismiss: () -> Unit,
    onAccountCreated: ((AccountId) -> Unit)? = null,
    initialName: String = "",
    // Account names are globally unique (account.name UNIQUE). Passing the current names lets the dialog
    // reject a clash before submitting instead of surfacing a raw constraint-violation message; callers
    // that don't have the list handy still fail safely via the submit-time catch below.
    existingNames: Set<String> = emptySet(),
) {
    val accountState = rememberAccountDialogState(initialName = initialName, initialCategoryId = -1L)
    var selectedOwnerIds by remember { mutableStateOf(setOf<Long>()) }
    var selectedOwnerIdForAddition by remember { mutableStateOf<Long?>(null) }
    var ownerDropdownExpanded by remember { mutableStateOf(false) }

    val categories by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        categoryRepository.getAllCategories()
    }
    val people by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        personRepository.getAllPeople()
    }
    val scope = rememberSchemaAwareCoroutineScope()
    val importEngine = LocalImportEngine.current

    val nameFocusRequester = remember { FocusRequester() }

    // Focus the name field on open so the user can type immediately and Enter has a focused field to
    // route through (the key-event handler only fires while a field inside the dialog is focused).
    LaunchedEffect(Unit) { runCatching { nameFocusRequester.requestFocus() } }

    val submit: () -> Unit = submit@{
        if (accountState.isSaving) return@submit
        if (accountState.name.isBlank()) {
            accountState.errorMessage = "Account name is required"
            accountState.nameError = true
            nameFocusRequester.requestFocus()
        } else if (existingNames.contains(accountState.name.trim())) {
            accountState.errorMessage = "An account named \"${accountState.name.trim()}\" already exists"
            accountState.nameError = true
            nameFocusRequester.requestFocus()
        } else {
            accountState.isSaving = true
            accountState.errorMessage = null
            scope.launch {
                try {
                    val now = Clock.System.now()
                    val key = LocalAccountKey("new-account")
                    val result =
                        importEngine.import(
                            ImportBatch.manualEdits(
                                accounts =
                                    listOf(
                                        ImportAccountIntent(
                                            key = key,
                                            source = Source.Manual,
                                            name = accountState.name.trim(),
                                            openingDate = now,
                                            categoryId = accountState.selectedCategoryId,
                                        ),
                                    ),
                                ownerships =
                                    selectedOwnerIds.map { personId ->
                                        ImportOwnershipIntent(
                                            source = Source.Manual,
                                            existingPersonId = PersonId(personId),
                                            account = AccountRef.Local(key),
                                        )
                                    },
                            ),
                        )
                    onAccountCreated?.invoke(result.createdAccountIds.getValue(key))
                    onDismiss()
                } catch (expected: Exception) {
                    logger.error(expected) { "Failed to create account: ${expected.message}" }
                    accountState.errorMessage =
                        if (expected.message.isAccountNameUniqueViolation()) {
                            "An account named \"${accountState.name.trim()}\" already exists"
                        } else {
                            "Failed to create account: ${expected.message}"
                        }
                    accountState.isSaving = false
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!accountState.isSaving) onDismiss() },
        title = { Text("Create New Account") },
        text = {
            AccountDialogContent(
                accountState = accountState,
                categories = categories,
                modifier = Modifier.onEnterKeyDown(submit),
                nameFocusRequester = nameFocusRequester,
                onNameSubmit = submit,
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
                onClick = submit,
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
            onPersonCreated = { personId ->
                selectedOwnerIdForAddition = personId.id
            },
            onDismiss = { accountState.showCreatePersonDialog = false },
            personAttributeRepository = personAttributeRepository,
            attributeTypeRepository = attributeTypeRepository,
        )
    }
}

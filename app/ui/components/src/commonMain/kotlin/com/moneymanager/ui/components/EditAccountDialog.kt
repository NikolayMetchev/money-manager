@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.moneymanager.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.AttributeTypeReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipReadRepository
import com.moneymanager.domain.repository.PersonAttributeReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.ImportAccountIntent
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportOperation
import com.moneymanager.importengineapi.ImportOwnershipIntent
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.importengineapi.getOrCreateAttributeType
import com.moneymanager.ui.LocalImportEngine
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.screens.CreateCategoryDialog
import com.moneymanager.ui.screens.transactions.EditableAttributesSection
import com.moneymanager.ui.util.onEnterKeyDown
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

private val logger = logging()

/**
 * A dialog for editing an existing account with category, owner, and attribute selection.
 */
@Composable
fun EditAccountDialog(
    account: Account,
    accountAttributeRepository: AccountAttributeReadRepository,
    attributeTypeRepository: AttributeTypeReadRepository,
    categoryRepository: CategoryReadRepository,
    personRepository: PersonReadRepository,
    personAttributeRepository: PersonAttributeReadRepository? = null,
    personAccountOwnershipRepository: PersonAccountOwnershipReadRepository,
    onDismiss: () -> Unit,
) {
    val accountState = rememberAccountDialogState(initialName = account.name, initialCategoryId = account.categoryId)

    val categories by categoryRepository
        .getAllCategories()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val people by personRepository
        .getAllPeople()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val existingOwnerships by personAccountOwnershipRepository
        .getOwnershipsByAccount(account.id)
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())

    var selectedOwnerIds by remember(existingOwnerships) {
        mutableStateOf(existingOwnerships.map { it.personId.id }.toSet())
    }

    // Attribute state
    var existingAttributeTypes by remember { mutableStateOf<List<AttributeType>>(emptyList()) }
    var editableAttributes by remember { mutableStateOf<Map<Long, Pair<String, String>>>(emptyMap()) }
    var originalAttributeList by remember { mutableStateOf<List<com.moneymanager.domain.model.AccountAttribute>>(emptyList()) }
    var nextTempId by remember { mutableStateOf(-1L) }
    var attributesLoaded by remember { mutableStateOf(false) }

    // Load existing attribute types for autocomplete
    LaunchedEffect(Unit) {
        attributeTypeRepository.getAll().collect { types ->
            existingAttributeTypes = types
        }
    }

    // Load existing attributes for this account
    LaunchedEffect(account.id) {
        accountAttributeRepository.getByAccount(account.id).collect { attrs ->
            if (!attributesLoaded) {
                originalAttributeList = attrs
                editableAttributes =
                    attrs.associate { attr ->
                        attr.id to Pair(attr.attributeType.name, attr.value)
                    }
                attributesLoaded = true
            }
        }
    }

    val scope = rememberSchemaAwareCoroutineScope()
    val importEngine = LocalImportEngine.current

    val nameFocusRequester = remember { FocusRequester() }

    // Focus the name field on open so Enter has a focused field to route through (the key-event handler
    // only fires while a field inside the dialog is focused).
    LaunchedEffect(Unit) { runCatching { nameFocusRequester.requestFocus() } }

    val submit: () -> Unit = submit@{
        if (accountState.isSaving) return@submit
        if (accountState.name.isBlank()) {
            accountState.errorMessage = "Account name is required"
            accountState.nameError = true
            nameFocusRequester.requestFocus()
        } else {
            accountState.isSaving = true
            accountState.errorMessage = null
            scope.launch {
                try {
                    // Determine if account fields actually changed
                    val accountFieldsChanged =
                        accountState.name.trim() != account.name ||
                            accountState.selectedCategoryId != account.categoryId
                    val updatedAccount =
                        if (accountFieldsChanged) {
                            account.copy(
                                name = accountState.name.trim(),
                                categoryId = accountState.selectedCategoryId,
                            )
                        } else {
                            null
                        }

                    // Resolve attribute type IDs before the atomic update
                    val originalIds = originalAttributeList.map { it.id }.toSet()
                    val editableIds = editableAttributes.keys.filter { it > 0 }.toSet()
                    val deletedAttributeIds = originalIds - editableIds

                    val updatedAttributes = mutableMapOf<Long, NewAttribute>()
                    editableAttributes.filter { (id, _) -> id > 0 }.forEach { (id, pair) ->
                        val (typeName, value) = pair
                        val original = originalAttributeList.find { it.id == id }
                        if (original != null) {
                            val typeChanged = original.attributeType.name != typeName
                            val valueChanged = original.value != value
                            if (typeChanged || valueChanged) {
                                val typeId = importEngine.getOrCreateAttributeType(typeName.trim())
                                updatedAttributes[id] = NewAttribute(typeId, value.trim())
                            }
                        }
                    }

                    val newAttributes = mutableListOf<NewAttribute>()
                    editableAttributes.filter { (id, _) -> id < 0 }.forEach { (_, pair) ->
                        val (typeName, value) = pair
                        if (typeName.isNotBlank() && value.isNotBlank()) {
                            val typeId = importEngine.getOrCreateAttributeType(typeName.trim())
                            newAttributes.add(NewAttribute(typeId, value.trim()))
                        }
                    }

                    val existingOwnerIds = existingOwnerships.map { it.personId.id }.toSet()
                    val ownersToAdd = selectedOwnerIds - existingOwnerIds
                    val ownersToRemove = existingOwnerIds - selectedOwnerIds

                    // One atomic batch: the account update (one revision bump for fields +
                    // attributes) plus the ownership add/remove rows.
                    importEngine.import(
                        ImportBatch.manualEdits(
                            accounts =
                                listOf(
                                    ImportAccountIntent(
                                        key = LocalAccountKey("edit"),
                                        source = Source.Manual,
                                        operation = ImportOperation.UPDATE,
                                        existingId = account.id,
                                        account = updatedAccount,
                                        deletedAttributeIds = deletedAttributeIds,
                                        updatedAttributes = updatedAttributes,
                                        attributes = newAttributes,
                                    ),
                                ),
                            ownerships =
                                ownersToRemove.mapNotNull { personId ->
                                    existingOwnerships.find { it.personId.id == personId }?.let {
                                        ImportOwnershipIntent(
                                            source = Source.Manual,
                                            operation = ImportOperation.DELETE,
                                            existingId = it.id,
                                        )
                                    }
                                } +
                                    ownersToAdd.map { personId ->
                                        ImportOwnershipIntent(
                                            source = Source.Manual,
                                            existingPersonId = PersonId(personId),
                                            account = AccountRef.Existing(account.id),
                                        )
                                    },
                        ),
                    )

                    onDismiss()
                } catch (expected: Exception) {
                    logger.error(expected) { "Failed to update account: ${expected.message}" }
                    accountState.errorMessage = "Failed to update account: ${expected.message}"
                    accountState.isSaving = false
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!accountState.isSaving) onDismiss() },
        title = { Text("Edit Account") },
        text = {
            AccountDialogContent(
                accountState = accountState,
                categories = categories,
                modifier = Modifier.verticalScroll(rememberScrollState()).onEnterKeyDown(submit),
                nameFocusRequester = nameFocusRequester,
                onNameSubmit = submit,
            ) {
                AccountOwnersSection(hasPeople = people.isNotEmpty()) {
                    people.forEach { person ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
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
                                enabled = !accountState.isSaving,
                            )
                            Text(
                                text = person.fullName,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    TextButton(
                        onClick = { accountState.showCreatePersonDialog = true },
                        enabled = !accountState.isSaving,
                    ) {
                        Text("+ Add New Person")
                    }
                }

                EditableAttributesSection(
                    editableAttributes = editableAttributes,
                    existingAttributeTypes = existingAttributeTypes,
                    isSaving = accountState.isSaving,
                    onAttributesChange = { editableAttributes = it },
                    onAddAttribute = {
                        editableAttributes = editableAttributes + (nextTempId to Pair("", ""))
                        nextTempId--
                    },
                )

                accountState.errorMessage?.let { error -> ErrorMessageText(error) }
            }
        },
        confirmButton = {
            LoadingTextButton(
                onClick = submit,
                enabled = !accountState.isSaving,
                loading = accountState.isSaving,
                label = "Save",
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
            onDismiss = { accountState.showCreatePersonDialog = false },
            personAttributeRepository = personAttributeRepository,
            attributeTypeRepository = attributeTypeRepository,
        )
    }
}

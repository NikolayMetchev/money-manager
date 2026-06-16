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
import com.moneymanager.domain.EntitySource
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.EntityProvenance
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.repository.AccountAttributeRepository
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AttributeTypeRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipRepository
import com.moneymanager.domain.repository.PersonAttributeRepository
import com.moneymanager.domain.repository.PersonRepository
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.screens.CreateCategoryDialog
import com.moneymanager.ui.screens.transactions.EditableAttributesSection
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

private val logger = logging()

/**
 * A dialog for editing an existing account with category, owner, and attribute selection.
 */
@Composable
fun EditAccountDialog(
    account: Account,
    accountRepository: AccountRepository,
    accountAttributeRepository: AccountAttributeRepository,
    attributeTypeRepository: AttributeTypeRepository,
    categoryRepository: CategoryRepository,
    personRepository: PersonRepository,
    personAttributeRepository: PersonAttributeRepository? = null,
    personAccountOwnershipRepository: PersonAccountOwnershipRepository,
    entitySource: EntitySource,
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

    AlertDialog(
        onDismissRequest = { if (!accountState.isSaving) onDismiss() },
        title = { Text("Edit Account") },
        text = {
            AccountDialogContent(
                accountState = accountState,
                categories = categories,
                modifier = Modifier.verticalScroll(rememberScrollState()),
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
                onClick = {
                    if (accountState.name.isBlank()) {
                        accountState.errorMessage = "Account name is required"
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
                                            val typeId = attributeTypeRepository.getOrCreate(typeName.trim())
                                            updatedAttributes[id] = NewAttribute(typeId, value.trim())
                                        }
                                    }
                                }

                                val newAttributes = mutableListOf<NewAttribute>()
                                editableAttributes.filter { (id, _) -> id < 0 }.forEach { (_, pair) ->
                                    val (typeName, value) = pair
                                    if (typeName.isNotBlank() && value.isNotBlank()) {
                                        val typeId = attributeTypeRepository.getOrCreate(typeName.trim())
                                        newAttributes.add(NewAttribute(typeId, value.trim()))
                                    }
                                }

                                val provenance = EntityProvenance.Manual(entitySource.deviceId)

                                // Atomic update: one revision bump for account + all attribute changes.
                                // The source for the resulting revision is recorded inside the repository.
                                accountRepository.updateAccountWithAttributes(
                                    account = updatedAccount,
                                    accountId = account.id,
                                    deletedAttributeIds = deletedAttributeIds,
                                    updatedAttributes = updatedAttributes,
                                    newAttributes = newAttributes,
                                    provenance = provenance,
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
                                    personAccountOwnershipRepository.createOwnership(
                                        personId = PersonId(personId),
                                        accountId = account.id,
                                        provenance = provenance,
                                    )
                                }

                                onDismiss()
                            } catch (expected: Exception) {
                                logger.error(expected) { "Failed to update account: ${expected.message}" }
                                accountState.errorMessage = "Failed to update account: ${expected.message}"
                                accountState.isSaving = false
                            }
                        }
                    }
                },
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
            personRepository = personRepository,
            entitySource = entitySource,
            onDismiss = { accountState.showCreatePersonDialog = false },
            personAttributeRepository = personAttributeRepository,
            attributeTypeRepository = attributeTypeRepository,
        )
    }
}

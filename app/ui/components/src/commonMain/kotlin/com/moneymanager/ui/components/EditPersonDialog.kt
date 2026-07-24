package com.moneymanager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonAttribute
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.repository.AttributeTypeReadRepository
import com.moneymanager.domain.repository.PersonAttributeReadRepository
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportOperation
import com.moneymanager.importengineapi.ImportPersonIntent
import com.moneymanager.importengineapi.LocalPersonKey
import com.moneymanager.importengineapi.getOrCreateAttributeType
import com.moneymanager.ui.components.transactions.EditableAttribute
import com.moneymanager.ui.components.transactions.EditableAttributesSection
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.foundation.LocalImportEngine
import com.moneymanager.ui.util.onEnterKeyDown
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

private val logger = logging()

@Composable
fun EditPersonDialog(
    personToEdit: Person?,
    onDismiss: () -> Unit,
    onPersonCreated: ((PersonId) -> Unit)? = null,
    personAttributeRepository: PersonAttributeReadRepository? = null,
    attributeTypeRepository: AttributeTypeReadRepository? = null,
) {
    var firstName by remember { mutableStateOf(personToEdit?.firstName.orEmpty()) }
    var middleName by remember { mutableStateOf(personToEdit?.middleName.orEmpty()) }
    var lastName by remember { mutableStateOf(personToEdit?.lastName.orEmpty()) }
    val saveState = rememberDialogSaveState()

    // Attribute editing (e.g. provider external ids) is available when both repositories are provided.
    val attributesEditable = personAttributeRepository != null && attributeTypeRepository != null
    var existingAttributeTypes by remember { mutableStateOf<List<AttributeType>>(emptyList()) }
    var editableAttributes by remember { mutableStateOf<Map<Long, EditableAttribute>>(emptyMap()) }
    var originalAttributeList by remember { mutableStateOf<List<PersonAttribute>>(emptyList()) }
    var nextTempId by remember { mutableStateOf(-1L) }
    var attributesLoaded by remember { mutableStateOf(false) }

    val scope = rememberSchemaAwareCoroutineScope()
    val importEngine = LocalImportEngine.current

    LaunchedEffect(Unit) {
        attributeTypeRepository?.getAll()?.collect { types -> existingAttributeTypes = types }
    }

    LaunchedEffect(personToEdit?.id) {
        if (personAttributeRepository != null && personToEdit != null && !attributesLoaded) {
            val attrs = personAttributeRepository.getByPerson(personToEdit.id).first()
            originalAttributeList = attrs
            editableAttributes = attrs.associate { it.id to EditableAttribute(it.attributeType.name, it.value, it.groupKey) }
            attributesLoaded = true
        }
    }

    val firstNameFocusRequester = remember { FocusRequester() }
    var firstNameError by remember { mutableStateOf(false) }

    // Focus the first field on open so the user can type immediately and Enter has a focused field to
    // route through (the key-event handler only fires while a field inside the dialog is focused).
    LaunchedEffect(Unit) { runCatching { firstNameFocusRequester.requestFocus() } }

    val submit: () -> Unit = submit@{
        if (saveState.isSaving) return@submit
        if (firstName.isBlank()) {
            saveState.errorMessage = "First name is required"
            firstNameError = true
            firstNameFocusRequester.requestFocus()
        } else {
            saveState.isSaving = true
            saveState.errorMessage = null
            scope.launch {
                try {
                    // Build the attribute delta (delete/update/insert) the way the account
                    // dialog does, so the whole person edit is one gated, atomic engine call.
                    val deletedAttributeIds = mutableSetOf<Long>()
                    val updatedAttributes = mutableMapOf<Long, NewAttribute>()
                    val newAttributes = mutableListOf<NewAttribute>()
                    if (attributesEditable) {
                        val keptIds = editableAttributes.keys.filter { it > 0 }.toSet()
                        originalAttributeList
                            .map { it.id }
                            .toSet()
                            .minus(keptIds)
                            .forEach { deletedAttributeIds.add(it) }
                        editableAttributes.filterKeys { it > 0 }.forEach { (id, attribute) ->
                            val typeName = attribute.typeName.trim()
                            val value = attribute.value.trim()
                            val original = originalAttributeList.find { it.id == id } ?: return@forEach
                            when {
                                typeName.isBlank() || value.isBlank() -> deletedAttributeIds.add(id)
                                original.attributeType.name != typeName || original.value != value ->
                                    updatedAttributes[id] =
                                        NewAttribute(
                                            importEngine.getOrCreateAttributeType(typeName),
                                            value,
                                            attribute.groupKey,
                                        )
                            }
                        }
                        editableAttributes.filterKeys { it < 0 }.forEach { (_, attribute) ->
                            val typeName = attribute.typeName.trim()
                            val value = attribute.value.trim()
                            if (typeName.isNotEmpty() && value.isNotEmpty()) {
                                newAttributes.add(
                                    NewAttribute(
                                        importEngine.getOrCreateAttributeType(typeName),
                                        value,
                                        attribute.groupKey,
                                    ),
                                )
                            }
                        }
                    }
                    val newPersonId =
                        if (personToEdit != null) {
                            importEngine.import(
                                ImportBatch.manualEdits(
                                    people =
                                        listOf(
                                            ImportPersonIntent(
                                                key = LocalPersonKey("edit"),
                                                source = Source.Manual,
                                                operation = ImportOperation.UPDATE,
                                                existingId = personToEdit.id,
                                                person =
                                                    personToEdit.copy(
                                                        firstName = firstName.trim(),
                                                        middleName = middleName.trim().ifBlank { null },
                                                        lastName = lastName.trim().ifBlank { null },
                                                    ),
                                                deletedAttributeIds = deletedAttributeIds,
                                                updatedAttributes = updatedAttributes,
                                                attributes = newAttributes,
                                            ),
                                        ),
                                ),
                            )
                            null
                        } else {
                            // CREATE carries its attributes, so the engine creates the row and
                            // its attributes in one go (no follow-up update needed).
                            val key = LocalPersonKey("new-person")
                            val result =
                                importEngine.import(
                                    ImportBatch.manualEdits(
                                        people =
                                            listOf(
                                                ImportPersonIntent(
                                                    key = key,
                                                    source = Source.Manual,
                                                    firstName = firstName.trim(),
                                                    middleName = middleName.trim().ifBlank { null },
                                                    lastName = lastName.trim().ifBlank { null },
                                                    attributes = newAttributes,
                                                ),
                                            ),
                                    ),
                                )
                            result.createdPersonIds.getValue(key)
                        }
                    // Only announce the new person once the full save (row + attributes)
                    // has succeeded, so the parent never selects a half-saved person.
                    if (newPersonId != null) {
                        onPersonCreated?.invoke(newPersonId)
                    }
                    onDismiss()
                } catch (expected: Exception) {
                    val action = if (personToEdit != null) "update" else "create"
                    logger.error(expected) { "Failed to $action person: ${expected.message}" }
                    saveState.errorMessage = "Failed to $action person: ${expected.message}"
                    saveState.isSaving = false
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saveState.isSaving) onDismiss() },
        title = { Text(if (personToEdit != null) "Edit Person" else "Create New Person") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .onEnterKeyDown(submit),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = firstName,
                    onValueChange = {
                        firstName = it
                        firstNameError = false
                    },
                    label = { Text("First Name *") },
                    modifier = Modifier.fillMaxWidth().focusRequester(firstNameFocusRequester).onEnterKeyDown(submit),
                    singleLine = true,
                    enabled = !saveState.isSaving,
                    isError = firstNameError,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )

                OutlinedTextField(
                    value = middleName,
                    onValueChange = { middleName = it },
                    label = { Text("Middle Name(s)") },
                    modifier = Modifier.fillMaxWidth().onEnterKeyDown(submit),
                    singleLine = true,
                    enabled = !saveState.isSaving,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )

                OutlinedTextField(
                    value = lastName,
                    onValueChange = { lastName = it },
                    label = { Text("Last Name") },
                    modifier = Modifier.fillMaxWidth().onEnterKeyDown(submit),
                    singleLine = true,
                    enabled = !saveState.isSaving,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )

                if (attributesEditable) {
                    EditableAttributesSection(
                        editableAttributes = editableAttributes,
                        existingAttributeTypes = existingAttributeTypes,
                        isSaving = saveState.isSaving,
                        onAttributesChange = { editableAttributes = it },
                        onAddAttribute = {
                            editableAttributes = editableAttributes + (nextTempId to EditableAttribute("", "", it))
                            nextTempId--
                        },
                    )
                }

                saveState.errorMessage?.let { error -> ErrorMessageText(error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = submit,
                enabled = !saveState.isSaving,
            ) {
                if (saveState.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(if (personToEdit != null) "Save" else "Create")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !saveState.isSaving,
            ) {
                Text("Cancel")
            }
        },
    )
}

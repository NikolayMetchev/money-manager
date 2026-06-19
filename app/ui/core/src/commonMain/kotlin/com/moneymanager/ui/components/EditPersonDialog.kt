@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonAttribute
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.repository.AttributeTypeRepository
import com.moneymanager.domain.repository.PersonAttributeRepository
import com.moneymanager.ui.LocalImportEngine
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.screens.transactions.EditableAttributesSection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

private val logger = logging()

@Composable
fun EditPersonDialog(
    personToEdit: Person?,
    onDismiss: () -> Unit,
    onPersonCreated: ((PersonId) -> Unit)? = null,
    personAttributeRepository: PersonAttributeRepository? = null,
    attributeTypeRepository: AttributeTypeRepository? = null,
) {
    var firstName by remember { mutableStateOf(personToEdit?.firstName.orEmpty()) }
    var middleName by remember { mutableStateOf(personToEdit?.middleName.orEmpty()) }
    var lastName by remember { mutableStateOf(personToEdit?.lastName.orEmpty()) }
    val saveState = rememberDialogSaveState()

    // Attribute editing (e.g. provider external ids) is available when both repositories are provided.
    val attributesEditable = personAttributeRepository != null && attributeTypeRepository != null
    var existingAttributeTypes by remember { mutableStateOf<List<AttributeType>>(emptyList()) }
    var editableAttributes by remember { mutableStateOf<Map<Long, Pair<String, String>>>(emptyMap()) }
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
            editableAttributes = attrs.associate { it.id to Pair(it.attributeType.name, it.value) }
            attributesLoaded = true
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
                        .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = firstName,
                    onValueChange = { firstName = it },
                    label = { Text("First Name *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !saveState.isSaving,
                )

                OutlinedTextField(
                    value = middleName,
                    onValueChange = { middleName = it },
                    label = { Text("Middle Name(s)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !saveState.isSaving,
                )

                OutlinedTextField(
                    value = lastName,
                    onValueChange = { lastName = it },
                    label = { Text("Last Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !saveState.isSaving,
                )

                if (attributesEditable) {
                    EditableAttributesSection(
                        editableAttributes = editableAttributes,
                        existingAttributeTypes = existingAttributeTypes,
                        isSaving = saveState.isSaving,
                        onAttributesChange = { editableAttributes = it },
                        onAddAttribute = {
                            editableAttributes = editableAttributes + (nextTempId to Pair("", ""))
                            nextTempId--
                        },
                    )
                }

                saveState.errorMessage?.let { error -> ErrorMessageText(error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (firstName.isBlank()) {
                        saveState.errorMessage = "First name is required"
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
                                    editableAttributes.filterKeys { it > 0 }.forEach { (id, pair) ->
                                        val typeName = pair.first.trim()
                                        val value = pair.second.trim()
                                        val original = originalAttributeList.find { it.id == id } ?: return@forEach
                                        when {
                                            typeName.isBlank() || value.isBlank() -> deletedAttributeIds.add(id)
                                            original.attributeType.name != typeName || original.value != value ->
                                                updatedAttributes[id] = NewAttribute(attributeTypeRepository.getOrCreate(typeName), value)
                                        }
                                    }
                                    editableAttributes.filterKeys { it < 0 }.forEach { (_, pair) ->
                                        val typeName = pair.first.trim()
                                        val value = pair.second.trim()
                                        if (typeName.isNotEmpty() && value.isNotEmpty()) {
                                            newAttributes.add(NewAttribute(attributeTypeRepository.getOrCreate(typeName), value))
                                        }
                                    }
                                }
                                val newPersonId =
                                    if (personToEdit != null) {
                                        importEngine.updatePersonWithAttributes(
                                            person =
                                                personToEdit.copy(
                                                    firstName = firstName.trim(),
                                                    middleName = middleName.trim().ifBlank { null },
                                                    lastName = lastName.trim().ifBlank { null },
                                                ),
                                            personId = personToEdit.id,
                                            deletedAttributeIds = deletedAttributeIds,
                                            updatedAttributes = updatedAttributes,
                                            newAttributes = newAttributes,
                                            source = Source.Manual,
                                        )
                                        null
                                    } else {
                                        val createdId =
                                            importEngine.createPerson(
                                                Person(
                                                    id = PersonId(0),
                                                    firstName = firstName.trim(),
                                                    middleName = middleName.trim().ifBlank { null },
                                                    lastName = lastName.trim().ifBlank { null },
                                                ),
                                                Source.Manual,
                                            )
                                        if (newAttributes.isNotEmpty()) {
                                            importEngine.updatePersonWithAttributes(
                                                person = null,
                                                personId = createdId,
                                                deletedAttributeIds = emptySet(),
                                                updatedAttributes = emptyMap(),
                                                newAttributes = newAttributes,
                                                source = Source.Manual,
                                            )
                                        }
                                        createdId
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
                },
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

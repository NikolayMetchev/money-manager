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
import com.moneymanager.domain.EntitySource
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonAttribute
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.repository.AttributeTypeRepository
import com.moneymanager.domain.repository.PersonAttributeRepository
import com.moneymanager.domain.repository.PersonRepository
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.screens.transactions.EditableAttributesSection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

private val logger = logging()

@Composable
fun EditPersonDialog(
    personToEdit: Person?,
    personRepository: PersonRepository,
    entitySource: EntitySource,
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
                                val personId =
                                    if (personToEdit != null) {
                                        personRepository.updatePerson(
                                            personToEdit.copy(
                                                firstName = firstName.trim(),
                                                middleName = middleName.trim().ifBlank { null },
                                                lastName = lastName.trim().ifBlank { null },
                                            ),
                                        )
                                        personToEdit.id
                                    } else {
                                        val newId =
                                            personRepository.createPerson(
                                                Person(
                                                    id = PersonId(0),
                                                    firstName = firstName.trim(),
                                                    middleName = middleName.trim().ifBlank { null },
                                                    lastName = lastName.trim().ifBlank { null },
                                                ),
                                            )
                                        entitySource.record(EntityType.PERSON, newId.id, 1L)
                                        onPersonCreated?.invoke(newId)
                                        newId
                                    }
                                if (personAttributeRepository != null && attributeTypeRepository != null) {
                                    savePersonAttributes(
                                        personId = personId,
                                        editableAttributes = editableAttributes,
                                        originalAttributeList = originalAttributeList,
                                        personAttributeRepository = personAttributeRepository,
                                        attributeTypeRepository = attributeTypeRepository,
                                    )
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

/** Persists the dialog's attribute edits: deletes removed rows, updates changed ones, inserts new ones. */
private suspend fun savePersonAttributes(
    personId: PersonId,
    editableAttributes: Map<Long, Pair<String, String>>,
    originalAttributeList: List<PersonAttribute>,
    personAttributeRepository: PersonAttributeRepository,
    attributeTypeRepository: AttributeTypeRepository,
) {
    val keptIds = editableAttributes.keys.filter { it > 0 }.toSet()
    originalAttributeList
        .map { it.id }
        .toSet()
        .minus(keptIds)
        .forEach { personAttributeRepository.delete(it) }

    editableAttributes.filterKeys { it > 0 }.forEach { (id, pair) ->
        val typeName = pair.first.trim()
        val value = pair.second.trim()
        val original = originalAttributeList.find { it.id == id } ?: return@forEach
        when {
            typeName.isBlank() || value.isBlank() -> personAttributeRepository.delete(id)
            original.attributeType.name != typeName -> {
                personAttributeRepository.delete(id)
                personAttributeRepository.insert(personId, attributeTypeRepository.getOrCreate(typeName), value)
            }
            original.value != value -> personAttributeRepository.updateValue(id, value)
        }
    }

    editableAttributes.filterKeys { it < 0 }.forEach { (_, pair) ->
        val typeName = pair.first.trim()
        val value = pair.second.trim()
        if (typeName.isNotEmpty() && value.isNotEmpty()) {
            personAttributeRepository.insert(personId, attributeTypeRepository.getOrCreate(typeName), value)
        }
    }
}

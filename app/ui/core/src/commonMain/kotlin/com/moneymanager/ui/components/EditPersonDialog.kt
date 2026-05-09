@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.database.ManualEntitySourceRecorder
import com.moneymanager.database.sql.EntitySourceQueries
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.repository.AttributeTypeRepository
import com.moneymanager.domain.repository.PersonAttributeRepository
import com.moneymanager.domain.repository.PersonRepository
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.screens.transactions.AttributeTypeField
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

private val logger = logging()

@Composable
fun EditPersonDialog(
    personToEdit: Person?,
    personRepository: PersonRepository,
    entitySourceQueries: EntitySourceQueries,
    deviceId: DeviceId,
    onDismiss: () -> Unit,
    personAttributeRepository: PersonAttributeRepository? = null,
    attributeTypeRepository: AttributeTypeRepository? = null,
) {
    var firstName by remember { mutableStateOf(personToEdit?.firstName.orEmpty()) }
    var middleName by remember { mutableStateOf(personToEdit?.middleName.orEmpty()) }
    var lastName by remember { mutableStateOf(personToEdit?.lastName.orEmpty()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    var existingAttributeTypes by remember { mutableStateOf<List<AttributeType>>(emptyList()) }
    var editableAttributes by remember { mutableStateOf<Map<Long, Pair<String, String>>>(emptyMap()) }
    var originalAttributeList by remember { mutableStateOf<List<com.moneymanager.domain.model.PersonAttribute>>(emptyList()) }
    var nextTempId by remember { mutableStateOf(-1L) }
    var attributesLoaded by remember { mutableStateOf(false) }

    if (personToEdit != null && attributeTypeRepository != null && personAttributeRepository != null) {
        LaunchedEffect(Unit) {
            attributeTypeRepository.getAll().collect { types ->
                existingAttributeTypes = types
            }
        }

        LaunchedEffect(personToEdit.id) {
            personAttributeRepository.getByPerson(personToEdit.id).collect { attrs ->
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
    }

    val scope = rememberSchemaAwareCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(if (personToEdit != null) "Edit Person" else "Create New Person") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = firstName,
                    onValueChange = { firstName = it },
                    label = { Text("First Name *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isSaving,
                )

                OutlinedTextField(
                    value = middleName,
                    onValueChange = { middleName = it },
                    label = { Text("Middle Name(s)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isSaving,
                )

                OutlinedTextField(
                    value = lastName,
                    onValueChange = { lastName = it },
                    label = { Text("Last Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isSaving,
                )

                if (personToEdit != null && personAttributeRepository != null && attributeTypeRepository != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Attributes",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        editableAttributes.forEach { (id, pair) ->
                            val (typeName, value) = pair
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AttributeTypeField(
                                    value = typeName,
                                    onValueChange = { newTypeName ->
                                        editableAttributes = editableAttributes + (id to Pair(newTypeName, value))
                                    },
                                    existingTypes = existingAttributeTypes,
                                    enabled = !isSaving,
                                    modifier = Modifier.weight(0.4f),
                                )
                                OutlinedTextField(
                                    value = value,
                                    onValueChange = { newValue ->
                                        editableAttributes = editableAttributes + (id to Pair(typeName, newValue))
                                    },
                                    label = { Text("Value") },
                                    modifier = Modifier.weight(0.5f),
                                    singleLine = true,
                                    enabled = !isSaving,
                                )
                                IconButton(
                                    onClick = {
                                        editableAttributes = editableAttributes - id
                                    },
                                    enabled = !isSaving,
                                ) {
                                    Text(
                                        text = "X",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                            }
                        }

                        TextButton(
                            onClick = {
                                editableAttributes = editableAttributes + (nextTempId to Pair("", ""))
                                nextTempId--
                            },
                            enabled = !isSaving,
                        ) {
                            Text("+ Add Attribute")
                        }
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
                    if (firstName.isBlank()) {
                        errorMessage = "First name is required"
                    } else {
                        isSaving = true
                        errorMessage = null
                        scope.launch {
                            try {
                                if (personToEdit != null) {
                                    val updatedPerson =
                                        personToEdit.copy(
                                            firstName = firstName.trim(),
                                            middleName = middleName.trim().ifBlank { null },
                                            lastName = lastName.trim().ifBlank { null },
                                        )
                                    if (attributeTypeRepository != null && personAttributeRepository != null) {
                                        val originalIds = originalAttributeList.map { it.id }.toSet()
                                        val editableIds = editableAttributes.keys.filter { it > 0 }.toSet()
                                        val deletedAttributeIds = originalIds - editableIds

                                        val updatedAttributes = mutableMapOf<Long, NewAttribute>()
                                        editableAttributes.filter { (id, _) -> id > 0 }.forEach { (id, pair) ->
                                            val (typeName, value) = pair
                                            val original = originalAttributeList.find { it.id == id }
                                            if (original != null) {
                                                if (original.attributeType.name != typeName || original.value != value) {
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

                                        val personFieldsChanged =
                                            firstName.trim() != personToEdit.firstName ||
                                                middleName.trim().ifBlank { null } != personToEdit.middleName ||
                                                lastName.trim().ifBlank { null } != personToEdit.lastName
                                        val finalRevisionId =
                                            personRepository.updatePersonWithAttributes(
                                                person = if (personFieldsChanged) updatedPerson else null,
                                                personId = personToEdit.id,
                                                deletedAttributeIds = deletedAttributeIds,
                                                updatedAttributes = updatedAttributes,
                                                newAttributes = newAttributes,
                                            )
                                        ManualEntitySourceRecorder(entitySourceQueries, deviceId).insert(
                                            EntityType.PERSON,
                                            personToEdit.id.id,
                                            finalRevisionId,
                                        )
                                    } else {
                                        personRepository.updatePerson(updatedPerson)
                                    }
                                } else {
                                    val newPerson =
                                        Person(
                                            id = PersonId(0),
                                            firstName = firstName.trim(),
                                            middleName = middleName.trim().ifBlank { null },
                                            lastName = lastName.trim().ifBlank { null },
                                        )
                                    val personId = personRepository.createPerson(newPerson)
                                    ManualEntitySourceRecorder(entitySourceQueries, deviceId).insert(
                                        EntityType.PERSON,
                                        personId.id,
                                        1L,
                                    )
                                }
                                onDismiss()
                            } catch (expected: Exception) {
                                val action = if (personToEdit != null) "update" else "create"
                                logger.error(expected) { "Failed to $action person: ${expected.message}" }
                                errorMessage = "Failed to $action person: ${expected.message}"
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
                    Text(if (personToEdit != null) "Save" else "Create")
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
}

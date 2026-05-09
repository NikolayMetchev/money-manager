@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import com.moneymanager.database.DatabaseConfig
import com.moneymanager.database.ManualEntitySourceRecorder
import com.moneymanager.database.sql.EntitySourceQueries
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.repository.PersonAttributeRepository
import com.moneymanager.domain.repository.PersonRepository
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

private val logger = logging()

@Composable
fun EditPersonDialog(
    personToEdit: Person?,
    personRepository: PersonRepository,
    personAttributeRepository: PersonAttributeRepository? = null,
    entitySourceQueries: EntitySourceQueries,
    deviceId: DeviceId,
    onDismiss: () -> Unit,
) {
    var firstName by remember { mutableStateOf(personToEdit?.firstName.orEmpty()) }
    var middleName by remember { mutableStateOf(personToEdit?.middleName.orEmpty()) }
    var lastName by remember { mutableStateOf(personToEdit?.lastName.orEmpty()) }
    var externalId by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    val scope = rememberSchemaAwareCoroutineScope()

    LaunchedEffect(personToEdit?.id) {
        externalId =
            if (personAttributeRepository != null) {
                personToEdit
                    ?.let { person ->
                        personAttributeRepository
                            .getByPerson(person.id)
                            .first()
                            .firstOrNull { it.attributeType.id.id == DatabaseConfig.PERSON_EXTERNAL_ID_ATTR_TYPE_ID }
                            ?.value
                            .orEmpty()
                    }.orEmpty()
            } else {
                ""
            }
    }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
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

                if (personAttributeRepository != null) {
                    OutlinedTextField(
                        value = externalId,
                        onValueChange = { externalId = it },
                        label = { Text("External ID") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isSaving,
                    )
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
                                val resolvedExternalId = externalId.trim().ifBlank { null }
                                if (personToEdit != null) {
                                    val updatedPerson =
                                        personToEdit.copy(
                                            firstName = firstName.trim(),
                                            middleName = middleName.trim().ifBlank { null },
                                            lastName = lastName.trim().ifBlank { null },
                                        )
                                    personRepository.updatePerson(updatedPerson)
                                    if (personAttributeRepository != null) {
                                        upsertPersonExternalId(personToEdit.id, resolvedExternalId, personAttributeRepository)
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
                                    if (personAttributeRepository != null) {
                                        upsertPersonExternalId(personId, resolvedExternalId, personAttributeRepository)
                                    }
                                    // Record source for audit trail
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

private suspend fun upsertPersonExternalId(
    personId: PersonId,
    externalId: String?,
    personAttributeRepository: PersonAttributeRepository,
) {
    val attributeTypeId = AttributeTypeId(DatabaseConfig.PERSON_EXTERNAL_ID_ATTR_TYPE_ID)
    val existingAttributes =
        personAttributeRepository
            .getByPerson(personId)
            .first()
            .filter { it.attributeType.id == attributeTypeId }

    when {
        externalId == null && existingAttributes.isNotEmpty() ->
            existingAttributes.forEach { personAttributeRepository.delete(it.id) }
        externalId != null && existingAttributes.isEmpty() ->
            personAttributeRepository.insert(personId, attributeTypeId, externalId)
        externalId != null && existingAttributes.size == 1 && existingAttributes.first().value != externalId ->
            personAttributeRepository.updateValue(existingAttributes.first().id, externalId)
        externalId != null && existingAttributes.size > 1 -> {
            existingAttributes.drop(1).forEach { personAttributeRepository.delete(it.id) }
            val first = existingAttributes.first()
            if (first.value != externalId) {
                personAttributeRepository.updateValue(first.id, externalId)
            }
        }
    }
}

@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.moneymanager.compose.scrollbar.VerticalScrollbarForLazyList
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonAuditEntry
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.repository.AuditRepository
import com.moneymanager.domain.repository.PersonRepository
import com.moneymanager.ui.audit.FieldChange
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.lighthousegames.logging.logging

private val logger = logging()

@Composable
fun PersonAuditScreen(
    personId: PersonId,
    auditRepository: AuditRepository,
    personRepository: PersonRepository,
    onBack: () -> Unit,
) {
    var auditEntries by remember { mutableStateOf<List<PersonAuditEntry>>(emptyList()) }
    var currentPerson by remember { mutableStateOf<Person?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(personId) {
        isLoading = true
        errorMessage = null
        try {
            auditEntries = auditRepository.getAuditHistoryForPerson(personId)
            currentPerson = personRepository.getPersonById(personId).first()
        } catch (expected: Exception) {
            logger.error(expected) { "Failed to load audit history: ${expected.message}" }
            errorMessage = "Failed to load audit history: ${expected.message}"
        } finally {
            isLoading = false
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
    ) {
        // Header with back button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Text(
                    text = "\u2190",
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Person Audit: ${currentPerson?.fullName ?: personId}",
                style = MaterialTheme.typography.headlineSmall,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (errorMessage != null) {
            Text(
                text = errorMessage!!,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (auditEntries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No audit history found for this person.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val auditDiffs =
                remember(auditEntries, currentPerson) {
                    computePersonAuditDiffs(auditEntries, currentPerson)
                }

            val auditListState = rememberLazyListState()
            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    state = auditListState,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(auditDiffs, key = { it.auditId }) { diff ->
                        PersonAuditDiffCard(diff = diff)
                    }
                }
                VerticalScrollbarForLazyList(
                    lazyListState = auditListState,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }
    }
}

private data class PersonAuditDiff(
    val auditId: Long,
    val auditTimestamp: Instant,
    val auditType: AuditType,
    val personId: PersonId,
    val revisionId: Long,
    val firstName: FieldChange<String>,
    val middleName: FieldChange<String?>,
    val lastName: FieldChange<String?>,
) {
    val hasChanges: Boolean
        get() = listOf(firstName, middleName, lastName).any { it is FieldChange.Changed }

    val fullName: String
        get() =
            buildString {
                append(firstName.value())
                middleName.value()?.takeIf { it.isNotBlank() }?.let {
                    append(" ")
                    append(it)
                }
                lastName.value()?.takeIf { it.isNotBlank() }?.let {
                    append(" ")
                    append(it)
                }
            }
}

private fun computePersonAuditDiffs(
    entries: List<PersonAuditEntry>,
    currentPerson: Person?,
): List<PersonAuditDiff> {
    return entries.mapIndexed { index, entry ->
        when (entry.auditType) {
            AuditType.INSERT ->
                PersonAuditDiff(
                    auditId = entry.auditId,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
                    personId = entry.personId,
                    revisionId = entry.revisionId,
                    firstName = FieldChange.Created(entry.firstName),
                    middleName = FieldChange.Created(entry.middleName),
                    lastName = FieldChange.Created(entry.lastName),
                )
            AuditType.DELETE ->
                PersonAuditDiff(
                    auditId = entry.auditId,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
                    personId = entry.personId,
                    revisionId = entry.revisionId,
                    firstName = FieldChange.Deleted(entry.firstName),
                    middleName = FieldChange.Deleted(entry.middleName),
                    lastName = FieldChange.Deleted(entry.lastName),
                )
            AuditType.UPDATE -> {
                // For UPDATE, entry stores OLD values
                // NEW values come from current person (if index 0) or next audit entry
                val newFirstName =
                    if (index == 0 && currentPerson != null) {
                        currentPerson.firstName
                    } else if (index > 0) {
                        entries[index - 1].firstName
                    } else {
                        entry.firstName
                    }
                val newMiddleName =
                    if (index == 0 && currentPerson != null) {
                        currentPerson.middleName
                    } else if (index > 0) {
                        entries[index - 1].middleName
                    } else {
                        entry.middleName
                    }
                val newLastName =
                    if (index == 0 && currentPerson != null) {
                        currentPerson.lastName
                    } else if (index > 0) {
                        entries[index - 1].lastName
                    } else {
                        entry.lastName
                    }

                PersonAuditDiff(
                    auditId = entry.auditId,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
                    personId = entry.personId,
                    revisionId = entry.revisionId,
                    firstName =
                        if (entry.firstName != newFirstName) {
                            FieldChange.Changed(entry.firstName, newFirstName)
                        } else {
                            FieldChange.Unchanged(entry.firstName)
                        },
                    middleName =
                        if (entry.middleName != newMiddleName) {
                            FieldChange.Changed(entry.middleName, newMiddleName)
                        } else {
                            FieldChange.Unchanged(entry.middleName)
                        },
                    lastName =
                        if (entry.lastName != newLastName) {
                            FieldChange.Changed(entry.lastName, newLastName)
                        } else {
                            FieldChange.Unchanged(entry.lastName)
                        },
                )
            }
        }
    }
}

@Composable
private fun PersonAuditDiffCard(diff: PersonAuditDiff) {
    val auditDateTime = diff.auditTimestamp.toLocalDateTime(TimeZone.currentSystemDefault())

    val (headerColor, headerText, containerColor) =
        when (diff.auditType) {
            AuditType.INSERT ->
                Triple(
                    MaterialTheme.colorScheme.primary,
                    "Created",
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                )
            AuditType.UPDATE ->
                Triple(
                    MaterialTheme.colorScheme.tertiary,
                    "Updated",
                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f),
                )
            AuditType.DELETE ->
                Triple(
                    MaterialTheme.colorScheme.error,
                    "Deleted",
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                )
        }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = headerText,
                        style = MaterialTheme.typography.titleMedium,
                        color = headerColor,
                    )
                    Text(
                        text = "Rev ${diff.revisionId}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "${auditDateTime.date} ${auditDateTime.time}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Content
            when (diff.auditType) {
                AuditType.INSERT -> {
                    Text(
                        text = "Created with:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FieldValueRow("First Name", diff.firstName.value())
                    FieldValueRow("Middle Name", diff.middleName.value() ?: "(none)")
                    FieldValueRow("Last Name", diff.lastName.value() ?: "(none)")
                }
                AuditType.UPDATE -> {
                    if (!diff.hasChanges) {
                        Text(
                            text = "No visible changes recorded",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = "Changed:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val firstNameChange = diff.firstName
                        if (firstNameChange is FieldChange.Changed) {
                            FieldChangeRow("First Name", firstNameChange.oldValue, firstNameChange.newValue)
                        }
                        val middleNameChange = diff.middleName
                        if (middleNameChange is FieldChange.Changed) {
                            FieldChangeRow(
                                "Middle Name",
                                middleNameChange.oldValue ?: "(none)",
                                middleNameChange.newValue ?: "(none)",
                            )
                        }
                        val lastNameChange = diff.lastName
                        if (lastNameChange is FieldChange.Changed) {
                            FieldChangeRow(
                                "Last Name",
                                lastNameChange.oldValue ?: "(none)",
                                lastNameChange.newValue ?: "(none)",
                            )
                        }
                    }
                }
                AuditType.DELETE -> {
                    val errorColor = MaterialTheme.colorScheme.error
                    Text(
                        text = "Deleted (final values):",
                        style = MaterialTheme.typography.labelMedium,
                        color = errorColor.copy(alpha = 0.8f),
                    )
                    FieldValueRow("First Name", diff.firstName.value(), errorColor)
                    FieldValueRow("Middle Name", diff.middleName.value() ?: "(none)", errorColor)
                    FieldValueRow("Last Name", diff.lastName.value() ?: "(none)", errorColor)
                }
            }
        }
    }
}

@Composable
private fun FieldValueRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
        )
    }
}

@Composable
private fun FieldChangeRow(
    label: String,
    oldValue: String,
    newValue: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp),
        )
        Text(
            text = oldValue,
            style =
                MaterialTheme.typography.bodyMedium.copy(
                    textDecoration = TextDecoration.LineThrough,
                ),
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
        )
        Text(
            text = "\u2192",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = newValue,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

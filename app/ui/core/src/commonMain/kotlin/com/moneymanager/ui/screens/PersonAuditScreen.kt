@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.EntitySource
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonAuditEntry
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.repository.AuditRepository
import com.moneymanager.domain.repository.PersonRepository
import com.moneymanager.ui.audit.AuditDiffCard
import com.moneymanager.ui.audit.AuditScreen
import com.moneymanager.ui.audit.AuditScreenData
import com.moneymanager.ui.audit.FieldChange
import com.moneymanager.ui.audit.FieldChangeRow
import com.moneymanager.ui.audit.FieldValueRow
import com.moneymanager.ui.audit.SourceInfoSection
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Instant

@Composable
fun PersonAuditScreen(
    personId: PersonId,
    auditRepository: AuditRepository,
    personRepository: PersonRepository,
    onBack: () -> Unit,
) {
    AuditScreen(
        defaultTitle = "Person Audit: $personId",
        entityTypeName = "person",
        loadKey = personId,
        loadData = {
            val entries = auditRepository.getAuditHistoryForPerson(personId)
            val currentPerson = personRepository.getPersonById(personId).first()
            val diffs = computePersonAuditDiffs(entries, currentPerson)
            AuditScreenData(
                title = "Person Audit: ${currentPerson?.fullName ?: personId}",
                diffs = diffs,
            )
        },
        diffKey = { it.id },
        onBack = onBack,
        diffCard = { diff -> PersonAuditDiffCard(diff) },
    )
}

private data class PersonAuditDiff(
    val id: Long,
    val auditTimestamp: Instant,
    val auditType: AuditType,
    val revisionId: Long,
    val firstName: FieldChange<String>,
    val middleName: FieldChange<String?>,
    val lastName: FieldChange<String?>,
    val source: EntitySource?,
) {
    val hasChanges: Boolean
        get() = listOf(firstName, middleName, lastName).any { it is FieldChange.Changed }
}

private fun computePersonAuditDiffs(
    entries: List<PersonAuditEntry>,
    currentPerson: Person?,
): List<PersonAuditDiff> {
    return entries.mapIndexed { index, entry ->
        when (entry.auditType) {
            AuditType.INSERT ->
                PersonAuditDiff(
                    id = entry.id,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
                    revisionId = entry.revisionId,
                    firstName = FieldChange.Created(entry.firstName),
                    middleName = FieldChange.Created(entry.middleName),
                    lastName = FieldChange.Created(entry.lastName),
                    source = entry.source,
                )
            AuditType.DELETE ->
                PersonAuditDiff(
                    id = entry.id,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
                    revisionId = entry.revisionId,
                    firstName = FieldChange.Deleted(entry.firstName),
                    middleName = FieldChange.Deleted(entry.middleName),
                    lastName = FieldChange.Deleted(entry.lastName),
                    source = entry.source,
                )
            AuditType.UPDATE -> {
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
                    id = entry.id,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
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
                    source = entry.source,
                )
            }
        }
    }
}

@Composable
private fun PersonAuditDiffCard(diff: PersonAuditDiff) {
    AuditDiffCard(
        auditType = diff.auditType,
        auditTimestamp = diff.auditTimestamp,
        revisionId = diff.revisionId,
    ) {
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
                SourceInfoSection(diff.source)
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
                SourceInfoSection(diff.source)
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
                SourceInfoSection(diff.source, labelColor = errorColor.copy(alpha = 0.8f))
            }
        }
    }
}

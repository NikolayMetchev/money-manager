package com.moneymanager.ui.screens.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonAttributeAuditEntry
import com.moneymanager.domain.model.PersonAuditEntry
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.QifImportId
import com.moneymanager.domain.model.SourceRecord
import com.moneymanager.domain.repository.AuditReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.ui.audit.AuditDiffCard
import com.moneymanager.ui.audit.AuditScreen
import com.moneymanager.ui.audit.AuditScreenData
import com.moneymanager.ui.audit.FieldChange
import com.moneymanager.ui.audit.FieldChangeRow
import com.moneymanager.ui.audit.FieldValueRow
import com.moneymanager.ui.audit.SourceInfoSection
import com.moneymanager.ui.audit.resolveUpdateChange
import kotlinx.coroutines.flow.first
import kotlin.time.Instant

@Composable
fun PersonAuditScreen(
    personId: PersonId,
    auditRepository: AuditReadRepository,
    personRepository: PersonReadRepository,
    onApiSourceClick: (ApiSessionId, ApiRequestId, String) -> Unit = { _, _, _ -> },
    onCsvSourceClick: (CsvImportId, Long) -> Unit = { _, _ -> },
    onQifSourceClick: (QifImportId, Long?) -> Unit = { _, _ -> },
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
        diffCard = { diff -> PersonAuditDiffCard(diff, onApiSourceClick, onCsvSourceClick, onQifSourceClick) },
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
    val attributeChanges: List<PersonAttributeAuditEntry>,
    val source: SourceRecord?,
) {
    val hasChanges: Boolean
        get() = listOf(firstName, middleName, lastName).any { it is FieldChange.Changed } || attributeChanges.isNotEmpty()
}

private fun computePersonAuditDiffs(
    entries: List<PersonAuditEntry>,
    currentPerson: Person?,
): List<PersonAuditDiff> =
    entries.mapIndexed { index, entry ->
        val revisionAttributes = entry.attributeChanges
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
                    attributeChanges = revisionAttributes,
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
                    attributeChanges = revisionAttributes,
                    source = entry.source,
                )
            AuditType.UPDATE -> {
                val previousEntry = entries.getOrNull(index - 1)

                PersonAuditDiff(
                    id = entry.id,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
                    revisionId = entry.revisionId,
                    firstName =
                        resolveUpdateChange(
                            index = index,
                            currentEntry = currentPerson,
                            previousEntry = previousEntry,
                            entryValue = entry.firstName,
                            currentValue = { it.firstName },
                            previousValue = { it.firstName },
                        ),
                    middleName =
                        resolveUpdateChange(
                            index = index,
                            currentEntry = currentPerson,
                            previousEntry = previousEntry,
                            entryValue = entry.middleName,
                            currentValue = { it.middleName },
                            previousValue = { it.middleName },
                        ),
                    lastName =
                        resolveUpdateChange(
                            index = index,
                            currentEntry = currentPerson,
                            previousEntry = previousEntry,
                            entryValue = entry.lastName,
                            currentValue = { it.lastName },
                            previousValue = { it.lastName },
                        ),
                    attributeChanges = revisionAttributes,
                    source = entry.source,
                )
            }
        }
    }

@Composable
private fun PersonAuditDiffCard(
    diff: PersonAuditDiff,
    onApiSourceClick: (ApiSessionId, ApiRequestId, String) -> Unit = { _, _, _ -> },
    onCsvSourceClick: (CsvImportId, Long) -> Unit = { _, _ -> },
    onQifSourceClick: (QifImportId, Long?) -> Unit = { _, _ -> },
) {
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
                PersonAttributeChangesSection(diff.attributeChanges)
                SourceInfoSection(
                    diff.source,
                    onApiSourceClick = onApiSourceClick,
                    onCsvSourceClick = onCsvSourceClick,
                    onQifSourceClick = onQifSourceClick,
                )
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
                    PersonAttributeChangesSection(diff.attributeChanges)
                }
                SourceInfoSection(
                    diff.source,
                    onApiSourceClick = onApiSourceClick,
                    onCsvSourceClick = onCsvSourceClick,
                    onQifSourceClick = onQifSourceClick,
                )
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
                PersonAttributeChangesSection(diff.attributeChanges, errorColor)
                SourceInfoSection(
                    diff.source,
                    labelColor = errorColor.copy(alpha = 0.8f),
                    onApiSourceClick = onApiSourceClick,
                    onCsvSourceClick = onCsvSourceClick,
                    onQifSourceClick = onQifSourceClick,
                )
            }
        }
    }
}

@Composable
private fun PersonAttributeChangesSection(
    attributeChanges: List<PersonAttributeAuditEntry>,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    if (attributeChanges.isEmpty()) return

    Column(
        modifier = Modifier.padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "Attributes:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        attributeChanges.forEach { attr ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = attr.attributeType.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(140.dp),
                )
                Text(
                    text = attr.value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = valueColor,
                )
            }
        }
    }
}

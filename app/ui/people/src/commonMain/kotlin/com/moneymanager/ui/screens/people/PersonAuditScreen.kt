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
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonAttributeAuditEntry
import com.moneymanager.domain.model.PersonAuditEntry
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.QifImportId
import com.moneymanager.domain.repository.AuditReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.ui.audit.AuditField
import com.moneymanager.ui.audit.AuditRevisionMeta
import com.moneymanager.ui.audit.AuditScreen
import com.moneymanager.ui.audit.AuditScreenData
import com.moneymanager.ui.audit.FlatEntityAuditDiffCard
import com.moneymanager.ui.audit.computeFlatEntityAuditDiffs
import kotlinx.coroutines.flow.first

private const val NO_VALUE = "(none)"

private val personAuditFields: List<AuditField<PersonAuditEntry, Person>> =
    listOf(
        AuditField(
            "First Name",
            fromEntry = { it.firstName },
            fromCurrent = { it.firstName },
        ),
        AuditField(
            "Middle Name",
            absent = NO_VALUE,
            fromEntry = { it.middleName },
            fromCurrent = { it.middleName },
        ),
        AuditField(
            "Last Name",
            absent = NO_VALUE,
            fromEntry = { it.lastName },
            fromCurrent = { it.lastName },
        ),
    )

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
            val diffs =
                computeFlatEntityAuditDiffs(
                    entries = entries,
                    current = currentPerson,
                    fields = personAuditFields,
                    hasExtraChanges = { it.attributeChanges.isNotEmpty() },
                ) { entry ->
                    AuditRevisionMeta(entry.id, entry.auditTimestamp, entry.auditType, entry.revisionId, entry.source)
                }
            AuditScreenData(
                title = "Person Audit: ${currentPerson?.fullName ?: personId}",
                diffs = diffs,
            )
        },
        diffKey = { it.id },
        onBack = onBack,
        diffCard = { diff ->
            FlatEntityAuditDiffCard(
                diff = diff,
                onApiSourceClick = onApiSourceClick,
                onCsvSourceClick = onCsvSourceClick,
                onQifSourceClick = onQifSourceClick,
            ) { entry, valueColor ->
                PersonAttributeChangesSection(entry.attributeChanges, valueColor)
            }
        },
    )
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

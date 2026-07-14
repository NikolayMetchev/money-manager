@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens.importdirectory

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.ImportDirectoryAuditEntry
import com.moneymanager.domain.model.ImportDirectoryId
import com.moneymanager.domain.model.SourceRecord
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.repository.AuditReadRepository
import com.moneymanager.domain.repository.ImportDirectoryReadRepository
import com.moneymanager.ui.audit.AuditDiffCard
import com.moneymanager.ui.audit.AuditScreen
import com.moneymanager.ui.audit.AuditScreenData
import com.moneymanager.ui.audit.AuditSectionLabel
import com.moneymanager.ui.audit.FieldChange
import com.moneymanager.ui.audit.FieldChangeRow
import com.moneymanager.ui.audit.FieldValueRow
import com.moneymanager.ui.audit.NoVisibleChangesText
import com.moneymanager.ui.audit.SourceInfoSection
import kotlinx.coroutines.flow.first
import kotlin.time.Instant

@Composable
fun ImportDirectoryAuditScreen(
    directoryId: ImportDirectoryId,
    auditRepository: AuditReadRepository,
    importDirectoryRepository: ImportDirectoryReadRepository,
    onBack: () -> Unit,
) {
    AuditScreen(
        defaultTitle = "Import Directory Audit: $directoryId",
        entityTypeName = "import directory",
        loadKey = directoryId,
        loadData = {
            val entries = auditRepository.getAuditHistoryForImportDirectory(directoryId)
            val current = importDirectoryRepository.getDirectoryById(directoryId).first()
            val diffs =
                computeImportDirectoryAuditDiffs(
                    entries = entries,
                    currentName = current?.name,
                    currentConfig = current?.flatten(),
                )
            AuditScreenData(
                title = "Import Directory Audit: ${current?.name ?: directoryId}",
                diffs = diffs,
            )
        },
        diffKey = { it.id },
        onBack = onBack,
        diffCard = { diff -> ImportDirectoryAuditDiffCard(diff) },
    )
}

// ─── Diff model ──────────────────────────────────────────────────────────────

private data class ImportDirectoryAuditDiff(
    val id: Long,
    val auditTimestamp: Instant,
    val auditType: AuditType,
    val revisionId: Long,
    val name: FieldChange<String>,
    val configChanges: List<Pair<String, FieldChange<String>>>,
    val source: SourceRecord?,
) {
    val hasChanges: Boolean
        get() = name is FieldChange.Changed || configChanges.isNotEmpty()
}

// ─── Diff computation ─────────────────────────────────────────────────────────

private fun computeImportDirectoryAuditDiffs(
    entries: List<ImportDirectoryAuditEntry>,
    currentName: String?,
    currentConfig: Map<String, String>?,
): List<ImportDirectoryAuditDiff> =
    entries.mapIndexed { index, entry ->
        when (entry.auditType) {
            AuditType.INSERT ->
                ImportDirectoryAuditDiff(
                    id = entry.id,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
                    revisionId = entry.revisionId,
                    name = FieldChange.Created(entry.name),
                    configChanges = emptyList(),
                    source = entry.source,
                )

            AuditType.DELETE ->
                ImportDirectoryAuditDiff(
                    id = entry.id,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
                    revisionId = entry.revisionId,
                    name = FieldChange.Deleted(entry.name),
                    configChanges = emptyList(),
                    source = entry.source,
                )

            AuditType.UPDATE -> {
                val previousEntry = entries.getOrNull(index - 1)
                val newName =
                    when {
                        index == 0 && currentName != null -> currentName
                        index > 0 && previousEntry != null -> previousEntry.name
                        else -> entry.name
                    }
                val newConfig =
                    when {
                        index == 0 -> currentConfig
                        previousEntry != null -> previousEntry.flatten()
                        else -> null
                    }
                ImportDirectoryAuditDiff(
                    id = entry.id,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
                    revisionId = entry.revisionId,
                    name = changedOrUnchanged(entry.name, newName),
                    configChanges = if (newConfig != null) diffConfigs(entry.flatten(), newConfig) else emptyList(),
                    source = entry.source,
                )
            }
        }
    }

private fun changedOrUnchanged(
    oldValue: String,
    newValue: String,
): FieldChange<String> = if (oldValue != newValue) FieldChange.Changed(oldValue, newValue) else FieldChange.Unchanged(newValue)

/** Returns only the fields that differ between [oldConfig] (before) and [newConfig] (after). */
private fun diffConfigs(
    oldConfig: Map<String, String>,
    newConfig: Map<String, String>,
): List<Pair<String, FieldChange<String>>> =
    oldConfig.keys
        .union(newConfig.keys)
        .sorted()
        .mapNotNull { key ->
            val o = oldConfig[key] ?: ""
            val n = newConfig[key] ?: ""
            if (o != n) key to FieldChange.Changed(o, n) else null
        }

private fun ImportDirectory.flatten(): Map<String, String> =
    buildMap {
        put("Provider", provider.name)
        put("Folder", displayPath ?: folderRef)
        put("Folder ref", folderRef)
        put("Top-level", topLevel.toString())
        put("Excluded", excluded.toString())
        parentId?.let { put("Parent", it.id.toString()) }
    }

private fun ImportDirectoryAuditEntry.flatten(): Map<String, String> =
    buildMap {
        put("Provider", providerType)
        put("Folder", displayPath ?: folderRef)
        put("Folder ref", folderRef)
        put("Top-level", topLevel.toString())
        put("Excluded", excluded.toString())
        parentId?.let { put("Parent", it.id.toString()) }
    }

// ─── Diff card ────────────────────────────────────────────────────────────────

@Composable
private fun ImportDirectoryAuditDiffCard(diff: ImportDirectoryAuditDiff) {
    AuditDiffCard(
        auditType = diff.auditType,
        auditTimestamp = diff.auditTimestamp,
        revisionId = diff.revisionId,
    ) {
        when (diff.auditType) {
            AuditType.INSERT -> {
                AuditSectionLabel("Created with:")
                FieldValueRow("Name", diff.name.value())
                SourceInfoSection(diff.source)
            }

            AuditType.UPDATE -> {
                if (!diff.hasChanges) {
                    NoVisibleChangesText()
                } else {
                    AuditSectionLabel("Changed:")
                    val nameChange = diff.name
                    if (nameChange is FieldChange.Changed) {
                        FieldChangeRow("Name", nameChange.oldValue, nameChange.newValue, labelWidth = 200.dp)
                    }
                    diff.configChanges.forEach { (label, change) ->
                        if (change is FieldChange.Changed) {
                            FieldChangeRow(label, change.oldValue, change.newValue, labelWidth = 200.dp)
                        }
                    }
                }
                SourceInfoSection(diff.source)
            }

            AuditType.DELETE -> {
                val errorColor = MaterialTheme.colorScheme.error
                AuditSectionLabel("Deleted (final values):")
                FieldValueRow("Name", diff.name.value(), errorColor)
                SourceInfoSection(diff.source, labelColor = errorColor.copy(alpha = 0.8f))
            }
        }
    }
}

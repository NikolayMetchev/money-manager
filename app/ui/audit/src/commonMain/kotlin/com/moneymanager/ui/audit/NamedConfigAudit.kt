package com.moneymanager.ui.audit

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.SourceRecord
import kotlin.time.Instant

/**
 * One audited revision of an entity that is just a **name plus a flat configuration** — a CSV or API
 * import strategy, an import directory. Each such screen only differs in how its entry type flattens
 * to [config]; everything downstream (diffing revision-over-revision, rendering the change card) is
 * the same, so it lives here once rather than once per screen.
 *
 * [config] is keyed by a stable field key; [computeNamedConfigAuditDiffs] applies the display label
 * only after diffing, so two fields that happen to share a label still diff independently.
 */
data class NamedConfigRevision(
    val id: Long,
    val auditTimestamp: Instant,
    val auditType: AuditType,
    val revisionId: Long,
    val name: String,
    val config: Map<String, String>,
    val source: SourceRecord?,
)

/** A single revision's changes, ready to render with [NamedConfigAuditDiffCard]. */
data class NamedConfigAuditDiff(
    val id: Long,
    val auditTimestamp: Instant,
    val auditType: AuditType,
    val revisionId: Long,
    val name: FieldChange<String>,
    val configChanges: List<Pair<String, FieldChange.Changed<String>>>,
    val source: SourceRecord?,
) {
    val hasChanges: Boolean
        get() = name is FieldChange.Changed || configChanges.isNotEmpty()
}

/**
 * Turns audit [revisions] (newest first) into per-revision diffs.
 *
 * An UPDATE row stores the values *before* the change, so the "after" side comes from the previous
 * (newer) revision, or from [currentName]/[currentConfig] for the newest row. [label] renames a
 * config key for display only.
 */
fun computeNamedConfigAuditDiffs(
    revisions: List<NamedConfigRevision>,
    currentName: String?,
    currentConfig: Map<String, String>?,
    label: (String) -> String = { it },
): List<NamedConfigAuditDiff> =
    revisions.mapIndexed { index, revision ->
        val previous = revisions.getOrNull(index - 1)
        when (revision.auditType) {
            AuditType.INSERT -> revision.toDiff(FieldChange.Created(revision.name), emptyList())
            AuditType.DELETE -> revision.toDiff(FieldChange.Deleted(revision.name), emptyList())
            AuditType.UPDATE -> {
                val newName =
                    when {
                        index == 0 && currentName != null -> currentName
                        index > 0 && previous != null -> previous.name
                        else -> revision.name
                    }
                val newConfig = if (index == 0) currentConfig else previous?.config
                revision.toDiff(
                    name = changedOrUnchanged(revision.name, newName),
                    configChanges = if (newConfig == null) emptyList() else diffConfigs(revision.config, newConfig, label),
                )
            }
        }
    }

private fun NamedConfigRevision.toDiff(
    name: FieldChange<String>,
    configChanges: List<Pair<String, FieldChange.Changed<String>>>,
) = NamedConfigAuditDiff(
    id = id,
    auditTimestamp = auditTimestamp,
    auditType = auditType,
    revisionId = revisionId,
    name = name,
    configChanges = configChanges,
    source = source,
)

/** The keys that differ between [oldConfig] (before this change) and [newConfig] (after), by label. */
private fun diffConfigs(
    oldConfig: Map<String, String>,
    newConfig: Map<String, String>,
    label: (String) -> String,
): List<Pair<String, FieldChange.Changed<String>>> =
    (oldConfig.keys + newConfig.keys)
        .mapNotNull { key ->
            val old = oldConfig[key].orEmpty()
            val new = newConfig[key].orEmpty()
            if (old != new) label(key) to FieldChange.Changed(old, new) else null
        }.sortedBy { it.first }

/** The card rendered for one [NamedConfigAuditDiff] in the audit list. */
@Composable
fun NamedConfigAuditDiffCard(diff: NamedConfigAuditDiff) {
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
                        FieldChangeRow(label, change.oldValue, change.newValue, labelWidth = 200.dp)
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

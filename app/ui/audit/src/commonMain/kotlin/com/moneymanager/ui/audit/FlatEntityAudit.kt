package com.moneymanager.ui.audit

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.QifImportId
import com.moneymanager.domain.model.SourceRecord
import kotlin.time.Instant

/**
 * One displayed field of a **flat entity** — an entity that is a fixed, ordered list of scalar
 * columns (a currency, a category, a person), as opposed to the name-plus-map entities handled by
 * [NamedConfigRevision].
 *
 * Field order is part of the display, so fields are given as an ordered list rather than a map.
 * [fromEntry] reads the value out of an audit row, [fromCurrent] out of the live entity; both may
 * return `null` for a column that is nullable, in which case [absent] is shown in its place.
 */
class AuditField<in E, in C>(
    val label: String,
    val absent: String = "",
    val fromEntry: (E) -> String?,
    val fromCurrent: (C) -> String?,
)

/** The audit-row metadata every flat-entity audit table carries, whatever its payload columns are. */
data class AuditRevisionMeta(
    val id: Long,
    val auditTimestamp: Instant,
    val auditType: AuditType,
    val revisionId: Long,
    val source: SourceRecord?,
)

/** One field's change within a revision, already resolved to display strings. */
data class FlatFieldChange(
    val label: String,
    val absent: String,
    val change: FieldChange<String?>,
) {
    /** The current (post-change) value, with [absent] substituted for a null column. */
    val displayValue: String get() = change.value() ?: absent

    /** This field's before/after pair, or `null` when it did not change in this revision. */
    val changedValues: Pair<String, String>?
        get() =
            (change as? FieldChange.Changed)?.let {
                (it.oldValue ?: absent) to (it.newValue ?: absent)
            }
}

/** A single revision's changes, ready to render with [FlatEntityAuditDiffCard]. */
data class FlatEntityAuditDiff<out E>(
    val meta: AuditRevisionMeta,
    val fields: List<FlatFieldChange>,
    val entry: E,
    val hasExtraChanges: Boolean = false,
) {
    val id: Long get() = meta.id

    val hasChanges: Boolean
        get() = hasExtraChanges || fields.any { it.change is FieldChange.Changed }
}

/**
 * Turns audit [entries] (newest first) into per-revision diffs.
 *
 * An UPDATE row stores the values *before* the change, so the "after" side comes from the previous
 * (newer) row, or from [current] for the newest row — the same resolution [resolveUpdateChange]
 * performs for a single field.
 *
 * [hasExtraChanges] reports revision content rendered outside [fields] (person attributes, say), so
 * that a revision which only touched that content is not shown as "no visible changes".
 */
fun <E : Any, C : Any> computeFlatEntityAuditDiffs(
    entries: List<E>,
    current: C?,
    fields: List<AuditField<E, C>>,
    hasExtraChanges: (E) -> Boolean = { false },
    meta: (E) -> AuditRevisionMeta,
): List<FlatEntityAuditDiff<E>> =
    entries.mapIndexed { index, entry ->
        val entryMeta = meta(entry)
        val previousEntry = entries.getOrNull(index - 1)
        val changes =
            fields.map { field ->
                val entryValue = field.fromEntry(entry)
                val change =
                    when (entryMeta.auditType) {
                        AuditType.INSERT -> FieldChange.Created(entryValue)
                        AuditType.DELETE -> FieldChange.Deleted(entryValue)
                        AuditType.UPDATE ->
                            resolveUpdateChange(
                                index = index,
                                currentEntry = current,
                                previousEntry = previousEntry,
                                entryValue = entryValue,
                                currentValue = field.fromCurrent,
                                previousValue = field.fromEntry,
                            )
                    }
                FlatFieldChange(field.label, field.absent, change)
            }
        FlatEntityAuditDiff(entryMeta, changes, entry, hasExtraChanges(entry))
    }

/**
 * The card rendered for one [FlatEntityAuditDiff] in the audit list.
 *
 * [extraSection] renders whatever the entity shows beyond its flat fields (person attributes, say);
 * it is handed the revision's entry and the value colour in force for this audit type.
 */
@Composable
fun <E> FlatEntityAuditDiffCard(
    diff: FlatEntityAuditDiff<E>,
    labelWidth: Dp = 100.dp,
    onApiSourceClick: ((ApiSessionId, ApiRequestId, String) -> Unit)? = null,
    onCsvSourceClick: ((CsvImportId, Long) -> Unit)? = null,
    onQifSourceClick: ((QifImportId, Long?) -> Unit)? = null,
    extraSection: @Composable (E, Color) -> Unit = { _, _ -> },
) {
    @Composable
    fun sourceInfo(labelColor: Color) {
        SourceInfoSection(
            source = diff.meta.source,
            labelColor = labelColor,
            labelWidth = labelWidth,
            onApiSourceClick = onApiSourceClick,
            onCsvSourceClick = onCsvSourceClick,
            onQifSourceClick = onQifSourceClick,
        )
    }

    AuditDiffCard(
        auditType = diff.meta.auditType,
        auditTimestamp = diff.meta.auditTimestamp,
        revisionId = diff.meta.revisionId,
    ) {
        when (diff.meta.auditType) {
            AuditType.INSERT -> {
                val valueColor = MaterialTheme.colorScheme.onSurface
                AuditSectionLabel("Created with:")
                diff.fields.forEach { FieldValueRow(it.label, it.displayValue, valueColor, labelWidth) }
                extraSection(diff.entry, valueColor)
                sourceInfo(MaterialTheme.colorScheme.onSurfaceVariant)
            }

            AuditType.UPDATE -> {
                if (!diff.hasChanges) {
                    NoVisibleChangesText()
                } else {
                    AuditSectionLabel("Changed:")
                    diff.fields.forEach { field ->
                        field.changedValues?.let { (old, new) ->
                            FieldChangeRow(field.label, old, new, labelWidth = labelWidth)
                        }
                    }
                    extraSection(diff.entry, MaterialTheme.colorScheme.onSurface)
                }
                sourceInfo(MaterialTheme.colorScheme.onSurfaceVariant)
            }

            AuditType.DELETE -> {
                val errorColor = MaterialTheme.colorScheme.error
                DeletedFinalValuesLabel(errorColor)
                diff.fields.forEach { FieldValueRow(it.label, it.displayValue, errorColor, labelWidth) }
                extraSection(diff.entry, errorColor)
                sourceInfo(errorColor.copy(alpha = 0.8f))
            }
        }
    }
}

@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.CategoryAuditEntry
import com.moneymanager.domain.model.EntitySource
import com.moneymanager.domain.repository.AuditRepository
import com.moneymanager.domain.repository.CategoryRepository
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
fun CategoryAuditScreen(
    categoryId: Long,
    auditRepository: AuditRepository,
    categoryRepository: CategoryRepository,
    onBack: () -> Unit,
) {
    AuditScreen(
        defaultTitle = "Category Audit: $categoryId",
        entityTypeName = "category",
        loadKey = categoryId,
        loadData = {
            val entries = auditRepository.getAuditHistoryForCategory(categoryId)
            val allCategories = categoryRepository.getAllCategories().first()
            val currentCategory = allCategories.find { it.id == categoryId }
            val diffs = computeCategoryAuditDiffs(entries, currentCategory, allCategories)
            AuditScreenData(
                title = "Category Audit: ${currentCategory?.name ?: categoryId}",
                diffs = diffs,
            )
        },
        diffKey = { it.id },
        onBack = onBack,
        diffCard = { diff -> CategoryAuditDiffCard(diff) },
    )
}

private data class CategoryAuditDiff(
    val id: Long,
    val auditTimestamp: Instant,
    val auditType: AuditType,
    val revisionId: Long,
    val name: FieldChange<String>,
    val parent: FieldChange<String>,
    val source: EntitySource?,
) {
    val hasChanges: Boolean
        get() = listOf(name, parent).any { it is FieldChange.Changed }
}

private fun computeCategoryAuditDiffs(
    entries: List<CategoryAuditEntry>,
    currentCategory: Category?,
    allCategories: List<Category>,
): List<CategoryAuditDiff> {
    fun formatParentDisplay(
        parentId: Long?,
        parentName: String?,
    ): String =
        when {
            parentId == null -> "(Top Level)"
            parentName != null -> parentName
            else -> "ID: $parentId"
        }

    fun getParentDisplay(
        parentId: Long?,
        parentName: String?,
    ): String = formatParentDisplay(parentId, parentName ?: allCategories.find { it.id == parentId }?.name)

    return entries.mapIndexed { index, entry ->
        when (entry.auditType) {
            AuditType.INSERT ->
                CategoryAuditDiff(
                    id = entry.id,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
                    revisionId = entry.revisionId,
                    name = FieldChange.Created(entry.name),
                    parent = FieldChange.Created(getParentDisplay(entry.parentId, entry.parentName)),
                    source = entry.source,
                )
            AuditType.DELETE ->
                CategoryAuditDiff(
                    id = entry.id,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
                    revisionId = entry.revisionId,
                    name = FieldChange.Deleted(entry.name),
                    parent = FieldChange.Deleted(getParentDisplay(entry.parentId, entry.parentName)),
                    source = entry.source,
                )
            AuditType.UPDATE -> {
                val newName =
                    if (index == 0 && currentCategory != null) {
                        currentCategory.name
                    } else if (index > 0) {
                        entries[index - 1].name
                    } else {
                        entry.name
                    }
                val newParentId =
                    if (index == 0 && currentCategory != null) {
                        currentCategory.parentId
                    } else if (index > 0) {
                        entries[index - 1].parentId
                    } else {
                        entry.parentId
                    }
                val newParentName =
                    if (index == 0 && currentCategory != null) {
                        allCategories.find { it.id == currentCategory.parentId }?.name
                    } else if (index > 0) {
                        entries[index - 1].parentName
                    } else {
                        entry.parentName
                    }

                val oldParentDisplay = getParentDisplay(entry.parentId, entry.parentName)
                val newParentDisplay = getParentDisplay(newParentId, newParentName)

                CategoryAuditDiff(
                    id = entry.id,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
                    revisionId = entry.revisionId,
                    name =
                        if (entry.name != newName) {
                            FieldChange.Changed(entry.name, newName)
                        } else {
                            FieldChange.Unchanged(entry.name)
                        },
                    parent =
                        if (oldParentDisplay != newParentDisplay) {
                            FieldChange.Changed(oldParentDisplay, newParentDisplay)
                        } else {
                            FieldChange.Unchanged(oldParentDisplay)
                        },
                    source = entry.source,
                )
            }
        }
    }
}

@Composable
private fun CategoryAuditDiffCard(diff: CategoryAuditDiff) {
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
                FieldValueRow("Name", diff.name.value())
                FieldValueRow("Parent", diff.parent.value())
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
                    val nameChange = diff.name
                    if (nameChange is FieldChange.Changed) {
                        FieldChangeRow("Name", nameChange.oldValue, nameChange.newValue)
                    }
                    val parentChange = diff.parent
                    if (parentChange is FieldChange.Changed) {
                        FieldChangeRow("Parent", parentChange.oldValue, parentChange.newValue)
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
                FieldValueRow("Name", diff.name.value(), errorColor)
                FieldValueRow("Parent", diff.parent.value(), errorColor)
                SourceInfoSection(diff.source, labelColor = errorColor.copy(alpha = 0.8f))
            }
        }
    }
}

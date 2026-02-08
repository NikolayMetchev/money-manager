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
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.CategoryAuditEntry
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.EntitySource
import com.moneymanager.domain.model.SourceType
import com.moneymanager.domain.repository.AuditRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.ui.audit.FieldChange
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.lighthousegames.logging.logging

private val logger = logging()

@Composable
fun CategoryAuditScreen(
    categoryId: Long,
    auditRepository: AuditRepository,
    categoryRepository: CategoryRepository,
    onBack: () -> Unit,
) {
    var auditEntries by remember { mutableStateOf<List<CategoryAuditEntry>>(emptyList()) }
    var currentCategory by remember { mutableStateOf<Category?>(null) }
    var allCategories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(categoryId) {
        isLoading = true
        errorMessage = null
        try {
            auditEntries = auditRepository.getAuditHistoryForCategory(categoryId)
            allCategories = categoryRepository.getAllCategories().first()
            currentCategory = allCategories.find { it.id == categoryId }
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
                text = "Category Audit: ${currentCategory?.name ?: categoryId}",
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
                    text = "No audit history found for this category.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val auditDiffs =
                remember(auditEntries, currentCategory, allCategories) {
                    computeCategoryAuditDiffs(auditEntries, currentCategory, allCategories)
                }

            val auditListState = rememberLazyListState()
            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    state = auditListState,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(auditDiffs, key = { it.id }) { diff ->
                        CategoryAuditDiffCard(diff = diff)
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

private data class CategoryAuditDiff(
    val id: Long,
    val auditTimestamp: Instant,
    val auditType: AuditType,
    val categoryId: Long,
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
                    categoryId = entry.categoryId,
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
                    categoryId = entry.categoryId,
                    revisionId = entry.revisionId,
                    name = FieldChange.Deleted(entry.name),
                    parent = FieldChange.Deleted(getParentDisplay(entry.parentId, entry.parentName)),
                    source = entry.source,
                )
            AuditType.UPDATE -> {
                // For UPDATE, entry stores OLD values
                // NEW values come from current category (if index 0) or next audit entry
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
                    categoryId = entry.categoryId,
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

@Composable
private fun SourceInfoSection(
    source: EntitySource?,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    if (source == null) return

    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Source:",
            style = MaterialTheme.typography.labelMedium,
            color = labelColor,
        )

        when (source.sourceType) {
            SourceType.MANUAL -> {
                val deviceInfo = source.deviceInfo
                when (deviceInfo) {
                    is DeviceInfo.Jvm -> {
                        FieldValueRow("Origin", "Manual (Desktop)")
                        FieldValueRow("Machine", deviceInfo.machineName)
                        FieldValueRow("OS", deviceInfo.osName)
                    }
                    is DeviceInfo.Android -> {
                        FieldValueRow("Origin", "Manual (Android)")
                        FieldValueRow("Device", "${deviceInfo.deviceMake} ${deviceInfo.deviceModel}")
                    }
                    null -> {
                        FieldValueRow("Origin", "Manual")
                    }
                }
            }
            SourceType.CSV_IMPORT -> {
                val deviceInfo = source.deviceInfo
                FieldValueRow("Origin", "CSV Import")
                when (deviceInfo) {
                    is DeviceInfo.Jvm -> {
                        FieldValueRow("Machine", deviceInfo.machineName)
                        FieldValueRow("OS", deviceInfo.osName)
                    }
                    is DeviceInfo.Android -> {
                        FieldValueRow("Device", "${deviceInfo.deviceMake} ${deviceInfo.deviceModel}")
                    }
                    null -> {}
                }
            }
            SourceType.SAMPLE_GENERATOR -> {
                val deviceInfo = source.deviceInfo
                when (deviceInfo) {
                    is DeviceInfo.Jvm -> {
                        FieldValueRow("Origin", "Sample Generator (Desktop)")
                        FieldValueRow("Machine", deviceInfo.machineName)
                        FieldValueRow("OS", deviceInfo.osName)
                    }
                    is DeviceInfo.Android -> {
                        FieldValueRow("Origin", "Sample Generator (Android)")
                        FieldValueRow("Device", "${deviceInfo.deviceMake} ${deviceInfo.deviceModel}")
                    }
                    null -> {
                        FieldValueRow("Origin", "Sample Generator")
                    }
                }
            }
            SourceType.SYSTEM -> {
                FieldValueRow("Origin", "System")
            }
        }
    }
}

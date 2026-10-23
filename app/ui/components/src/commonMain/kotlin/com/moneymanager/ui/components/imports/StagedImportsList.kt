package com.moneymanager.ui.components.imports

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.StagedImport
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.timeline.ImportFileDateRange
import kotlin.time.Instant

private val TREE_INDENT = 20.dp

/** What the list needs a file's card to show where it draws it; see [StagedImportCard]. */
data class StagedImportCardPlacement(
    val ignored: Boolean,
    // The Unimported tab's tree already places the file in its folder, so only the other tabs name it.
    val showDirectories: Boolean,
    // Auto-matched strategy name for an unimported file (null elsewhere, and null when unmatched).
    val matchedStrategyName: String? = null,
    val noMatchingStrategy: Boolean = false,
    val offerUnimport: Boolean = false,
    val indent: Dp = 0.dp,
)

/**
 * The Unimported / Imported / Ignored tabs of an import list screen, the same for every format: the
 * Unimported tab is a folder tree split by matched strategy, the Imported tab is grouped by the strategy
 * each file was applied with, and the Ignored tab is flat. Each folder and group offers its scoped bulk
 * action through [onImportAll], [onReimportAll] and (for formats that can unimport) [onUnimportAll];
 * [card] draws one file. [data] comes from [rememberStagedImportsData].
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun <K : Any, F : StagedImport<K>> StagedImportsTabs(
    imports: List<F>,
    data: StagedImportsData<K>,
    onImportAll: (List<F>) -> Unit,
    onReimportAll: (List<F>) -> Unit,
    onSetIgnored: (List<F>, Boolean) -> Unit,
    onUnimportAll: ((List<F>) -> Unit)? = null,
    card: @Composable (F, StagedImportCardPlacement) -> Unit,
) {
    // Split files into those still needing a strategy applied vs. already imported, so a large set of
    // files is easy to work through. The Unimported tab is the default/actionable one. Ignored files
    // are dismissed by the user and kept out of both actionable lists.
    val unimported = remember(imports) { imports.filter { !it.ignored && it.lastAppliedAt == null } }
    val importedList = remember(imports) { imports.filter { !it.ignored && it.lastAppliedAt != null } }
    val ignoredList = remember(imports) { imports.filter { it.ignored } }
    var selectedTab by remember { mutableStateOf(ImportTab.UNIMPORTED) }

    ImportTabsRow(
        selectedTab = selectedTab,
        unimportedCount = unimported.size,
        importedCount = importedList.size,
        ignoredCount = ignoredList.size,
        onTabSelected = { selectedTab = it },
    )

    Spacer(modifier = Modifier.height(12.dp))

    if (selectedTab == ImportTab.UNIMPORTED && unimported.isNotEmpty()) {
        Button(onClick = { onImportAll(unimported) }, modifier = Modifier.fillMaxWidth()) {
            Text("Import all (${unimported.size})")
        }
        Spacer(modifier = Modifier.height(12.dp))
    }

    if (selectedTab == ImportTab.IMPORTED && importedList.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onReimportAll(importedList) }, modifier = Modifier.weight(1f)) {
                Text("Re-import all (${importedList.size})")
            }
            if (onUnimportAll != null) {
                OutlinedButton(
                    onClick = { onUnimportAll(importedList) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Unimport all (${importedList.size})")
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }

    val shown =
        when (selectedTab) {
            ImportTab.UNIMPORTED -> unimported
            ImportTab.IMPORTED -> importedList
            else -> ignoredList
        }
    val expandedSections = remember { mutableStateMapOf<String, Boolean>() }
    when {
        shown.isEmpty() -> {
            ImportsEmptyMessage(emptyImportTabMessage(selectedTab))
        }

        selectedTab == ImportTab.IGNORED -> {
            // Ignored files span every strategy and need no scoped bulk action, so this tab stays flat.
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Indexed `items` throughout: Qodana reports the List overload's import as unused when the
                // element type is generic.
                items(shown.size, key = { shown[it].id.toString() }) { index ->
                    card(shown[index], StagedImportCardPlacement(ignored = true, showDirectories = true))
                }
            }
        }

        selectedTab == ImportTab.UNIMPORTED -> {
            val tree =
                remember(unimported, data.importDirectories, data.directories, data.matchedStrategies) {
                    buildUnimportedDirectoryTree(unimported, data.importDirectories, data.directories, data.matchedStrategies)
                }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tree.forEach { root ->
                    directoryTreeItems(
                        node = root,
                        depth = 0,
                        expandedSections = expandedSections,
                        onImportAll = onImportAll,
                        onIgnoreAll = { onSetIgnored(it, true) },
                    ) { import, group, indent ->
                        card(
                            import,
                            StagedImportCardPlacement(
                                ignored = false,
                                showDirectories = false,
                                matchedStrategyName = group.label.takeIf { group.actionable && !group.isWarning },
                                noMatchingStrategy = group.isWarning,
                                indent = indent,
                            ),
                        )
                    }
                }
            }
        }

        else -> {
            val groups = remember(importedList) { buildImportedStrategyGroups(importedList) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.forEach { group ->
                    val sectionKey = "imported:${group.key?.toString() ?: "none"}"
                    val expanded = expandedSections[sectionKey] ?: true
                    item(key = "header-$sectionKey") {
                        ImportSectionHeader(
                            title = group.label,
                            count = group.imports.size,
                            expanded = expanded,
                            onToggleExpanded = { expandedSections[sectionKey] = !expanded },
                            actionLabel = "Re-import all",
                            onAction = { onReimportAll(group.imports) },
                            isWarning = group.isWarning,
                            secondaryActionLabel = onUnimportAll?.let { "Unimport all" },
                            onSecondaryAction = { onUnimportAll?.invoke(group.imports) },
                        )
                    }
                    if (expanded) {
                        items(group.imports.size, key = { group.imports[it].id.toString() }) { index ->
                            card(
                                group.imports[index],
                                StagedImportCardPlacement(ignored = false, showDirectories = true, offerUnimport = true),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Emits [node]'s folder header and, when expanded, its subfolders (first, as in a file tree) then its
 * own files split by strategy, each level indented one [TREE_INDENT] further.
 */
@Suppress("LongParameterList")
private fun <F : StagedImport<*>> LazyListScope.directoryTreeItems(
    node: StagedImportDirectoryNode<F>,
    depth: Int,
    expandedSections: SnapshotStateMap<String, Boolean>,
    onImportAll: (List<F>) -> Unit,
    onIgnoreAll: (List<F>) -> Unit,
    card: @Composable (F, StagedImportStrategyGroup<F>, Dp) -> Unit,
) {
    val expanded = expandedSections[node.key] ?: true
    item(key = "header-${node.key}") {
        ImportSectionHeader(
            title = node.label,
            count = node.allImports.size,
            expanded = expanded,
            onToggleExpanded = { expandedSections[node.key] = !expanded },
            actionLabel = "Import all",
            onAction = { onImportAll(node.allImports) },
            isWarning = false,
            titleStyle = if (depth == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
            indent = TREE_INDENT * depth,
            badge = node.provider?.label(),
        )
    }
    if (!expanded) return
    node.children.forEach { child ->
        directoryTreeItems(child, depth + 1, expandedSections, onImportAll, onIgnoreAll, card)
    }
    val groupIndent = TREE_INDENT * (depth + 1)
    node.strategyGroups.forEach { group ->
        val sectionKey = "${node.key}:strategy:${group.key?.toString() ?: "none"}"
        val groupExpanded = expandedSections[sectionKey] ?: true
        item(key = "header-$sectionKey") {
            val isNoStrategyGroup = group.key == null && group.actionable
            // Named as a strategy, in a lighter style than a folder, so a folder's strategy split never
            // reads as subfolders.
            ImportSectionHeader(
                title = if (group.key != null) "Strategy: ${group.label}" else group.label,
                count = group.imports.size,
                expanded = groupExpanded,
                onToggleExpanded = { expandedSections[sectionKey] = !groupExpanded },
                actionLabel =
                    when {
                        !group.actionable -> null
                        isNoStrategyGroup -> "Ignore all"
                        else -> "Import all"
                    },
                onAction = { if (isNoStrategyGroup) onIgnoreAll(group.imports) else onImportAll(group.imports) },
                isWarning = group.isWarning,
                titleStyle = MaterialTheme.typography.labelLarge,
                indent = groupIndent,
            )
        }
        if (groupExpanded) {
            items(group.imports.size, key = { group.imports[it].id.toString() }) { index ->
                card(group.imports[index], group, groupIndent + TREE_INDENT)
            }
        }
    }
}

@Composable
private fun ImportSectionHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    actionLabel: String?,
    onAction: () -> Unit,
    isWarning: Boolean,
    titleStyle: TextStyle = MaterialTheme.typography.titleSmall,
    indent: Dp = 0.dp,
    // A small tag after the title, e.g. whether a directory is on Google Drive or local.
    badge: String? = null,
    // An extra, destructive action drawn before the main one (e.g. "Unimport all" beside "Re-import all").
    secondaryActionLabel: String? = null,
    onSecondaryAction: () -> Unit = {},
) {
    val titleColor = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpanded)
                .padding(start = indent, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = titleColor,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isWarning) "⚠ $title ($count)" else "$title ($count)",
                style = titleStyle,
                color = titleColor,
            )
            if (badge != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier =
                        Modifier
                            .background(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.shapes.small)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        if (secondaryActionLabel != null) {
            TextButton(onClick = onSecondaryAction) {
                Text(secondaryActionLabel, color = MaterialTheme.colorScheme.error)
            }
        }
        if (actionLabel != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * One file's card in [StagedImportsTabs]: the format's own [metadataText] (rows, records, …) plus the
 * lines every format shows — its source account, its directories, its matched strategy — as [placement]
 * asks for them.
 */
@Suppress("LongParameterList")
@Composable
fun StagedImportCard(
    import: StagedImport<*>,
    placement: StagedImportCardPlacement,
    metadataText: String,
    dateRange: ImportFileDateRange?,
    sourceAccountName: String?,
    sourceDirectories: List<ImportDirectory>,
    onClick: () -> Unit,
    onSetIgnored: (Boolean) -> Unit,
    // When the file was last unimported, if ever; null for formats that can't unimport.
    lastUnimportedAt: Instant? = null,
    // Null for formats that can't unimport.
    onUnimport: (() -> Unit)? = null,
) {
    val isImported = import.lastAppliedAt != null
    ImportFileCard(
        fileName = import.originalFileName,
        metadataText = metadataText,
        addedAt = import.importTimestamp,
        errorCount = import.errorCount,
        lastAppliedAt = import.lastAppliedAt,
        applicationCount = import.applicationCount,
        lastAppliedStrategyName = import.lastAppliedStrategyName,
        dateRange = dateRange,
        ignored = placement.ignored,
        onClick = onClick,
        onSetIgnored = onSetIgnored,
        modifier = Modifier.padding(start = placement.indent),
        lastUnimportedAt = lastUnimportedAt,
        onUnimport = onUnimport?.takeIf { placement.offerUnimport },
        details = { metadataColor ->
            ImportCardDetailText(
                text = "Source account: ${sourceAccountName ?: "Not set — choose at import"}",
                color = metadataColor,
            )
            if (placement.showDirectories) {
                val directoryText =
                    sourceDirectories
                        .joinToString { "${it.listLabel()} (${it.provider.label()})" }
                        .ifEmpty { MANUALLY_ADDED_LABEL }
                ImportCardDetailText(text = "Directory: $directoryText", color = metadataColor)
            }
            if (placement.matchedStrategyName != null) {
                ImportCardDetailText(text = "Strategy: ${placement.matchedStrategyName}", color = metadataColor)
            } else if (placement.noMatchingStrategy) {
                ImportCardDetailText(text = "⚠ No matching strategy", color = MaterialTheme.colorScheme.error)
            }
        },
        footer = { metadataColor ->
            if (isImported && import.lastAppliedStrategyName.isNullOrBlank()) {
                ImportCardDetailText(text = "Strategy information unavailable", color = metadataColor, spacing = 2.dp)
            }
            if (isImported && import.applicationCount > 1) {
                ImportCardDetailText(text = "Applied ${import.applicationCount} times", color = metadataColor, spacing = 2.dp)
            }
        },
    )
}

package com.moneymanager.ui.components.imports

import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.ImportDirectoryId
import com.moneymanager.domain.model.StagedImport
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.importdirectory.ImportDirectoryProvider

internal const val MANUALLY_ADDED_LABEL = "Manually added"

internal const val PATH_SEPARATOR = " / "

/**
 * One strategy's files within a tab; [key] is null for the "no strategy"/"unknown strategy" bucket.
 * [actionable] is false only for the transient "still matching" bucket, which has no scoped action yet.
 */
data class StagedImportStrategyGroup<F : StagedImport<*>>(
    val key: CsvImportStrategyId?,
    val label: String,
    val imports: List<F>,
    val isWarning: Boolean = false,
    val actionable: Boolean = true,
)

/**
 * A folder in the Unimported tab's directory tree. Roots are top-level import directories (plus a
 * [MANUALLY_ADDED_LABEL] root, [provider] null, for files added through the file picker); children are
 * subfolders derived from discovered directories' display paths. [strategyGroups] holds the files
 * directly in this folder; [allImports] everything beneath it, for the folder's "Import all".
 */
data class StagedImportDirectoryNode<F : StagedImport<*>>(
    val key: String,
    val label: String,
    val provider: ImportDirectoryProvider?,
    val strategyGroups: List<StagedImportStrategyGroup<F>>,
    val children: List<StagedImportDirectoryNode<F>>,
) {
    val allImports: List<F> = strategyGroups.flatMap { it.imports } + children.flatMap { it.allImports }
}

/** How an import directory is named in the imports lists; matches the import-directories screen. */
internal fun ImportDirectory.listLabel(): String = displayPath ?: folderRef

internal fun ImportDirectoryProvider.label(): String =
    when (this) {
        ImportDirectoryProvider.LOCAL -> "Local"
        ImportDirectoryProvider.GDRIVE -> "Google Drive"
    }

/**
 * Resolves each import's source directories (sorted by label) from [importDirectories] (see
 * `ImportDirectoryReadRepository.csvImportDirectories`). Directories no longer
 * known are dropped, so an import whose every directory was removed reads as manually added.
 */
fun <K> resolveImportDirectories(
    importDirectories: Map<K, List<ImportDirectoryId>>,
    directories: List<ImportDirectory>,
): Map<K, List<ImportDirectory>> {
    val byId = directories.associateBy { it.id }
    return importDirectories
        .mapValues { (_, ids) -> ids.distinct().mapNotNull(byId::get).sortedBy { it.listLabel() } }
        .filterValues { it.isNotEmpty() }
}

/**
 * Builds the Unimported tab's folder tree: each file sits under the first of its [importDirectories]
 * (a file staged from several directories is listed once), nested beneath its top-level directory by
 * subfolder path, and split by strategy within its folder (see [buildUnimportedStrategyGroups]). Folder
 * chains holding no files of their own collapse into one "A / B" node. Roots are sorted by label with
 * manually added files last.
 */
fun <K : Any, F : StagedImport<K>> buildUnimportedDirectoryTree(
    unimported: List<F>,
    importDirectories: Map<K, List<ImportDirectory>>,
    directories: List<ImportDirectory>,
    matches: Map<K, CsvImportStrategy?>?,
): List<StagedImportDirectoryNode<F>> {
    val byId = directories.associateBy { it.id }
    val (placed, manual) = unimported.partition { importDirectories[it.id]?.firstOrNull() != null }
    val roots =
        placed
            .groupBy { import ->
                val directory = importDirectories.getValue(import.id).first()
                directory.parentId?.let(byId::get) ?: directory
            }.map { (root, files) ->
                val entries = files.map { relativeSegments(importDirectories.getValue(it.id).first(), root) to it }
                buildNode("dir:${root.id}", root.listLabel(), root.provider, entries, matches)
            }.sortedBy { it.label.lowercase() }
    return buildList {
        addAll(roots)
        if (manual.isNotEmpty()) {
            add(buildNode("manual", MANUALLY_ADDED_LABEL, null, manual.map { emptyList<String>() to it }, matches))
        }
    }
}

// Discovered subfolders' display paths are the root's path plus " / "-joined subfolder names (see
// discoverImportableFolders); anything else is shown as a single child named by its full path.
private fun relativeSegments(
    directory: ImportDirectory,
    root: ImportDirectory,
): List<String> {
    if (directory.id == root.id) return emptyList()
    val prefix = "${root.listLabel()}$PATH_SEPARATOR"
    val path = directory.listLabel()
    return if (path.startsWith(prefix)) path.removePrefix(prefix).split(PATH_SEPARATOR) else listOf(path)
}

private fun <K : Any, F : StagedImport<K>> buildNode(
    key: String,
    label: String,
    provider: ImportDirectoryProvider?,
    entries: List<Pair<List<String>, F>>,
    matches: Map<K, CsvImportStrategy?>?,
): StagedImportDirectoryNode<F> {
    val (here, deeper) = entries.partition { it.first.isEmpty() }
    val children =
        deeper
            .groupBy { it.first.first() }
            .map { (segment, items) ->
                buildNode("$key/$segment", segment, provider, items.map { it.first.drop(1) to it.second }, matches).compacted()
            }.sortedBy { it.label.lowercase() }
    val strategyGroups = if (here.isEmpty()) emptyList() else buildUnimportedStrategyGroups(here.map { it.second }, matches)
    return StagedImportDirectoryNode(key, label, provider, strategyGroups, children)
}

private tailrec fun <F : StagedImport<*>> StagedImportDirectoryNode<F>.compacted(): StagedImportDirectoryNode<F> {
    val only = children.singleOrNull()
    if (strategyGroups.isNotEmpty() || only == null) return this
    return only.copy(label = "$label$PATH_SEPARATOR${only.label}").compacted()
}

/**
 * Groups already-imported files by the strategy they were last applied with — a field every
 * [StagedImport] already carries, so no extra queries are needed. Sorted by label, with a fallback
 * "Unknown strategy" bucket for imports whose applied strategy has since been deleted or otherwise lost
 * its name.
 */
fun <F : StagedImport<*>> buildImportedStrategyGroups(importedList: List<F>): List<StagedImportStrategyGroup<F>> =
    importedList
        .groupBy { it.lastAppliedStrategyId }
        .map { (id, files) ->
            val label = files.firstOrNull { !it.lastAppliedStrategyName.isNullOrBlank() }?.lastAppliedStrategyName
            StagedImportStrategyGroup(key = id, label = label ?: "Unknown strategy", imports = files)
        }.sortedBy { it.label.lowercase() }

/**
 * Groups unimported files by their auto-matched strategy ([matches], built the way "Import all" matches,
 * since these files have no stored strategy yet). Files with no match ([matches] value null) form a
 * "No strategy" group surfaced first with warning styling, so they're never lost among matched files.
 * While [matches] hasn't finished resolving, every file is shown under one "Matching strategies…" bucket
 * with no scoped action, rather than leaving the tab blank.
 */
fun <K : Any, F : StagedImport<K>> buildUnimportedStrategyGroups(
    unimported: List<F>,
    matches: Map<K, CsvImportStrategy?>?,
): List<StagedImportStrategyGroup<F>> {
    if (matches == null) {
        return listOf(
            StagedImportStrategyGroup(key = null, label = "Matching strategies…", imports = unimported, actionable = false),
        )
    }
    val byStrategyId = unimported.groupBy { matches[it.id]?.id }
    val noStrategy = byStrategyId[null].orEmpty()
    val strategyById = matches.values.filterNotNull().associateBy { it.id }
    val withStrategy =
        byStrategyId.entries
            .filter { it.key != null }
            .map { (id, files) ->
                StagedImportStrategyGroup(key = id, label = strategyById[id]?.name ?: "Unknown strategy", imports = files)
            }.sortedBy { it.label.lowercase() }
    return buildList {
        if (noStrategy.isNotEmpty()) {
            add(StagedImportStrategyGroup(key = null, label = "No strategy", imports = noStrategy, isWarning = true))
        }
        addAll(withStrategy)
    }
}

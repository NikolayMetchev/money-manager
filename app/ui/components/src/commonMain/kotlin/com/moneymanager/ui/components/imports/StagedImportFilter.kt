package com.moneymanager.ui.components.imports

import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.ImportDirectoryId
import com.moneymanager.domain.model.ImportFileKind
import com.moneymanager.domain.model.StagedImport
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.fileKind
import com.moneymanager.domain.model.importdirectory.ImportDirectory

internal const val NO_STRATEGY_LABEL = "No strategy"

/**
 * What the files list keeps: file names containing [text] (ignoring case), of one of [kinds], with one of
 * [strategies] (null: no strategy) and from one of [directories] (null: added by hand). An empty set keeps
 * every file. A directory also keeps the files of the folders beneath it, as the folder tree nests them.
 */
data class StagedImportFilter(
    val text: String = "",
    val kinds: Set<ImportFileKind> = emptySet(),
    val strategies: Set<CsvImportStrategyId?> = emptySet(),
    val directories: Set<ImportDirectoryId?> = emptySet(),
) {
    val isActive: Boolean
        get() = text.isNotBlank() || kinds.isNotEmpty() || strategies.isNotEmpty() || directories.isNotEmpty()
}

/** A strategy the files list can be filtered by; [id] null is the files with no strategy. */
data class StagedImportStrategyOption(
    val id: CsvImportStrategyId?,
    val label: String,
)

/**
 * The strategy [import] was applied with, else the one "Import all" matched for it; null when it has neither (or
 * matching hasn't finished).
 */
fun <K : Any> stagedImportStrategy(
    import: StagedImport<K>,
    matchedStrategies: Map<K, CsvImportStrategy?>?,
): CsvImportStrategyId? = import.lastAppliedStrategyId ?: matchedStrategies?.get(import.id)?.id

/** The files of [imports] that [filter] keeps, in their order. */
fun <K : Any, F : StagedImport<K>> filterStagedImports(
    imports: List<F>,
    filter: StagedImportFilter,
    importDirectories: Map<K, List<ImportDirectory>>,
    directories: List<ImportDirectory>,
    matchedStrategies: Map<K, CsvImportStrategy?>?,
): List<F> {
    if (!filter.isActive) return imports
    val text = filter.text.trim()
    val chosen = directories.filter { it.id in filter.directories }
    val chosenDirectories =
        directories
            .filter { directory -> chosen.any { directory.id == it.id || directory.isBeneath(it) } }
            .mapTo(mutableSetOf()) { it.id }
    return imports.filter { import ->
        val fileDirectories = importDirectories[import.id].orEmpty()
        (text.isEmpty() || import.originalFileName.contains(text, ignoreCase = true)) &&
            (filter.kinds.isEmpty() || import.fileKind in filter.kinds) &&
            (filter.strategies.isEmpty() || stagedImportStrategy(import, matchedStrategies) in filter.strategies) &&
            (
                filter.directories.isEmpty() ||
                    (fileDirectories.isEmpty() && null in filter.directories) ||
                    fileDirectories.any { it.id in chosenDirectories }
            )
    }
}

private fun ImportDirectory.isBeneath(folder: ImportDirectory): Boolean =
    provider == folder.provider && listLabel().startsWith(folder.listLabel() + PATH_SEPARATOR)

/** The kinds of [imports], in [ImportFileKind] order, for the file type filter. */
fun stagedImportKinds(imports: List<StagedImport<*>>): List<ImportFileKind> {
    val present = imports.mapNotNullTo(mutableSetOf()) { it.fileKind }
    return ImportFileKind.entries.filter { it in present }
}

/** The strategies [imports] have (see [stagedImportStrategy]), by name, then "No strategy" if any file has none. */
fun <K : Any> stagedImportStrategyOptions(
    imports: List<StagedImport<K>>,
    matchedStrategies: Map<K, CsvImportStrategy?>?,
): List<StagedImportStrategyOption> {
    val options =
        imports.map { import ->
            val id = stagedImportStrategy(import, matchedStrategies)
            val name =
                if (import.lastAppliedStrategyId != null) import.lastAppliedStrategyName else matchedStrategies?.get(import.id)?.name
            StagedImportStrategyOption(id, if (id == null) NO_STRATEGY_LABEL else name ?: id.toString())
        }
    val (none, named) = options.distinctBy { it.id }.partition { it.id == null }
    return named.sortedBy { it.label.lowercase() } + none
}

/**
 * The directories [imports] come from, with the top-level folders they sit under, by label; then "Manually added"
 * (key null) if any file was added by hand.
 */
fun <K : Any> stagedImportDirectoryOptions(
    imports: List<StagedImport<K>>,
    importDirectories: Map<K, List<ImportDirectory>>,
    directories: List<ImportDirectory>,
): List<Pair<ImportDirectoryId?, String>> {
    val fileDirectories = imports.map { importDirectories[it.id].orEmpty() }
    val present = fileDirectories.flatten()
    val parents = present.mapNotNullTo(mutableSetOf()) { it.parentId }
    val named =
        (present + directories.filter { it.id in parents })
            .distinctBy { it.id }
            .sortedBy { it.listLabel().lowercase() }
            .map { it.id to it.listLabel() }
    val manual = if (fileDirectories.any { it.isEmpty() }) listOf(null to MANUALLY_ADDED_LABEL) else emptyList()
    return named + manual
}

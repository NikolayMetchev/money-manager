package com.moneymanager.csvimporter

import com.moneymanager.importfilesource.ImportFileSource

/**
 * Traverses the folder tree rooted at [rootFolderRef] all the way down and returns every folder that
 * DIRECTLY contains an importable file (.csv/.qif), so the caller can create one import directory per
 * such folder. [openFolder] resolves an `ImportFileSource` for a folder ref; `displayPath` strings are
 * built from [rootDisplayPath] plus subfolder names for the UI. Cycles/symlinks are guarded by a
 * visited set and [maxDepth].
 */
suspend fun discoverImportableFolders(
    rootFolderRef: String,
    rootDisplayPath: String,
    openFolder: suspend (folderRef: String) -> ImportFileSource,
    maxDepth: Int = 25,
): List<DiscoveredImportFolder> {
    val found = mutableListOf<DiscoveredImportFolder>()
    val visited = mutableSetOf<String>()

    suspend fun visit(
        ref: String,
        path: String,
        depth: Int,
    ) {
        if (depth > maxDepth || !visited.add(ref)) return
        val source = openFolder(ref)
        if (source.list().any { isSupportedImportFile(it.name) }) {
            found += DiscoveredImportFolder(ref, path)
        }
        for (sub in source.listSubfolders()) {
            visit(sub.ref, "$path / ${sub.name}", depth + 1)
        }
    }

    visit(rootFolderRef, rootDisplayPath, 0)
    return found
}

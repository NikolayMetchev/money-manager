package com.moneymanager.csvimporter

import com.moneymanager.importfilesource.ImportFileSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Traverses the folder tree rooted at [rootFolderRef] all the way down and returns every folder that
 * DIRECTLY contains an importable file (.csv/.qif), so the caller can create one import directory per
 * such folder. [openFolder] resolves an `ImportFileSource` for a folder ref; `displayPath` strings are
 * built from [rootDisplayPath] plus subfolder names for the UI. Cycles/symlinks are guarded by a
 * visited set and [maxDepth]. [onFound] is called as each folder is found, before the whole walk ends,
 * so a caller can start on it early.
 */
suspend fun discoverImportableFolders(
    rootFolderRef: String,
    rootDisplayPath: String,
    openFolder: suspend (folderRef: String) -> ImportFileSource,
    maxDepth: Int = 25,
    onFound: suspend (DiscoveredImportFolder) -> Unit = {},
): List<DiscoveredImportFolder> {
    val visited = mutableSetOf<String>()
    val visitedLock = Mutex()

    // Each folder's listing and each subfolder's walk runs in its own coroutine; results are joined
    // per subtree in listing order, so the output stays depth-first pre-order despite the concurrency.
    suspend fun visit(
        ref: String,
        path: String,
        depth: Int,
    ): List<DiscoveredImportFolder> {
        if (depth > maxDepth || !visitedLock.withLock { visited.add(ref) }) return emptyList()
        val source = openFolder(ref)
        return coroutineScope {
            val hasImportableFile = async(Dispatchers.IO) { source.list().any { isSupportedImportFile(it.name) } }
            val subtrees =
                source.listSubfolders().map { sub ->
                    async(Dispatchers.IO) { visit(sub.ref, "$path / ${sub.name}", depth + 1) }
                }
            val self =
                if (hasImportableFile.await()) {
                    listOf(DiscoveredImportFolder(ref, path).also { onFound(it) })
                } else {
                    emptyList()
                }
            self + subtrees.awaitAll().flatten()
        }
    }

    return visit(rootFolderRef, rootDisplayPath, 0)
}

package com.moneymanager.importdirectory

import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.tasks.TaskManager
import com.moneymanager.tasks.TaskState

/** The import-directory runs (download, re-download, "Download all", …) among [this] task list's top-level tasks. */
fun List<TaskState>.importDirectoryRuns(): List<TaskState> =
    filter { it.parentId == null && it.key.startsWith(IMPORT_DIRECTORIES_TASK_PREFIX) }

/**
 * Whether a download of [directories] can start: one of them is readable here and no import-directory run is
 * going, as runs share folders.
 */
fun ImportDirectoryService.canDownload(
    directories: List<ImportDirectory>,
    tasks: List<TaskState>,
): Boolean = tasks.importDirectoryRuns().none { it.isRunning } && directories.any(this::isScannable)

/** Starts "Download all" on [taskManager]: every folder's new and changed files, and any new subfolders. */
fun ImportDirectoryService.startDownloadAll(taskManager: TaskManager) {
    taskManager.start(DOWNLOAD_ALL_TASK_KEY, "Download all", "Connecting…") { downloadAll(this).summary() }
}

/**
 * Starts downloading just [roots] (a folder of the files list and its subfolders) on [taskManager], the way
 * "Download all" does: new and changed files, and new subfolders of any top-level folder among them.
 */
fun ImportDirectoryService.startDownload(
    taskManager: TaskManager,
    title: String,
    roots: List<ImportDirectory>,
) {
    taskManager.start(foldersDownloadTaskKey(roots.map { it.id }), "Download $title", "Connecting…") {
        downloadAll(this, roots).summary()
    }
}

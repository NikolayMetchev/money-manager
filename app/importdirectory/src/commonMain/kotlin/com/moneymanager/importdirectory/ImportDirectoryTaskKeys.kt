package com.moneymanager.importdirectory

import com.moneymanager.domain.model.ImportDirectoryId

/** Top-level [com.moneymanager.tasks.TaskManager] keys every import-directory run starts under. */
const val IMPORT_DIRECTORIES_TASK_PREFIX = "import-directories-"

/** Key of the one "Download all" run. */
const val DOWNLOAD_ALL_TASK_KEY = "${IMPORT_DIRECTORIES_TASK_PREFIX}download-all"

/** Key of the one "Import all" run (download every folder, then apply every unimported file). */
const val IMPORT_ALL_TASK_KEY = "${IMPORT_DIRECTORIES_TASK_PREFIX}import-all"

/** Child-task key prefix of one folder's download or re-download, whichever run started it. */
const val FOLDER_DOWNLOAD_TASK_PREFIX = "import-directory-download-"

/** Child-task key prefix of one top-level folder's subfolder search. */
const val SUBFOLDER_SEARCH_TASK_PREFIX = "import-directory-subfolders-"

/** Key of a single folder's "Download" run. Distinct from its child's key, which the task manager would dedupe against. */
fun directoryDownloadTaskKey(id: ImportDirectoryId): String = "${IMPORT_DIRECTORIES_TASK_PREFIX}folder-${id.id}"

/** Key of a folder's "Re-download" run (it and its subfolders). */
fun directoryRedownloadTaskKey(id: ImportDirectoryId): String = "${IMPORT_DIRECTORIES_TASK_PREFIX}redownload-${id.id}"

/** Key of a download of some folders (a folder of the files list and its subfolders). */
fun foldersDownloadTaskKey(ids: List<ImportDirectoryId>): String =
    "${IMPORT_DIRECTORIES_TASK_PREFIX}folders-${ids.map { it.id.toString() }.sorted().joinToString(",")}"

fun folderDownloadTaskKey(id: ImportDirectoryId): String = "$FOLDER_DOWNLOAD_TASK_PREFIX${id.id}"

fun subfolderSearchTaskKey(id: ImportDirectoryId): String = "$SUBFOLDER_SEARCH_TASK_PREFIX${id.id}"

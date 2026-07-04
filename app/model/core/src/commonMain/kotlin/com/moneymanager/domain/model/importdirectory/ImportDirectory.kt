@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model.importdirectory

import com.moneymanager.domain.model.Auditable
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.Source
import kotlin.time.Instant

/** Where a directory's files live. [id] matches the import_directory_provider lookup row. */
enum class ImportDirectoryProvider(
    val id: Int,
) {
    /** A folder on the local filesystem (JVM path) or an Android document-tree URI. */
    LOCAL(1),

    /** A folder in the user's Google Drive. */
    GDRIVE(2),
    ;

    companion object {
        fun fromId(id: Long): ImportDirectoryProvider = entries.first { it.id.toLong() == id }
    }
}

/**
 * A configured import directory: a local folder or Google Drive folder. On a manual download the app
 * lists the folder and stages new/changed importable files (.csv/.qif) into the Imports tabs, tracked
 * per file via [ImportDirectoryFile].
 *
 * @property folderRef Whatever the platform backend needs to open the folder: a local absolute
 *   filesystem path (JVM), an Android Storage Access Framework tree/document URI, or a Drive folder id.
 * @property displayPath Human-readable full path shown in the UI (e.g. "My Drive / Statements"); null
 *   falls back to [folderRef] (e.g. JVM local folders, whose path ref is already readable).
 * @property providerConfig Provider-specific config (e.g. Drive OAuth client JSON); null for LOCAL.
 * @property deviceId Set for LOCAL directories (scanned only on that device); null for shared GDRIVE.
 * @property topLevel True for a user-picked folder (download discovers + creates child directories);
 *   false for a discovered subfolder (download fetches its own importable files).
 * @property parentId The top-level directory a discovered subfolder belongs to (for the tree view);
 *   null for top-level directories.
 * @property excluded When true the user excluded this directory: its download is disabled and
 *   "Download all" skips it.
 */
data class ImportDirectory(
    val id: ImportDirectoryId,
    val name: String,
    val provider: ImportDirectoryProvider,
    val folderRef: String,
    val displayPath: String? = null,
    val providerConfig: String? = null,
    val deviceId: DeviceId? = null,
    val topLevel: Boolean = true,
    val parentId: ImportDirectoryId? = null,
    val excluded: Boolean = false,
    val createdAt: Instant,
    val updatedAt: Instant,
    override val source: Source = Source.Manual,
) : Auditable

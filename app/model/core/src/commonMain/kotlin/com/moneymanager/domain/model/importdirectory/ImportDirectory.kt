@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model.importdirectory

import com.moneymanager.domain.model.Auditable
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.Source
import kotlin.time.Instant

/** Where a directory's files live. */
enum class ImportDirectoryProvider {
    /** A folder on the local filesystem (JVM path) or an Android document-tree URI. */
    LOCAL,

    /** A folder in the user's Google Drive. */
    GDRIVE,
}

/**
 * A configured import directory: a local folder or Google Drive folder pinned to one CSV import
 * strategy. On a manual scan the app lists the folder and imports new/changed files (tracked per
 * file via [ImportDirectoryFile]).
 *
 * @property folderRef Local absolute path / Android tree URI, or Drive folder id (used to access it).
 * @property displayPath Human-readable full path shown in the UI (e.g. "My Drive / Statements"); null
 *   falls back to [folderRef] (e.g. local folders, whose ref is already readable).
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

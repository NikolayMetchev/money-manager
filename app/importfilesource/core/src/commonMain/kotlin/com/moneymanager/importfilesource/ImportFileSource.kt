package com.moneymanager.importfilesource

import com.moneymanager.domain.model.importdirectory.ImportDirectory

/** A file found in an import directory. [ref] uniquely identifies the file within its folder. */
data class ImportFileEntry(
    val ref: String,
    val name: String,
    val lastModifiedEpochMs: Long?,
    val sizeBytes: Long? = null,
)

/** An immediate subfolder of an import directory. [ref] opens it (a path / Drive folder id). */
data class ImportSubfolder(
    val ref: String,
    val name: String,
)

/**
 * Read-only view of an import directory's files. Backends list a folder and fetch a file's raw bytes;
 * they hold no database state. One instance is bound to one configured directory (its folder is
 * implicit), so callers pass only file refs.
 */
interface ImportFileSource {
    /** Lists the files directly in the bound directory (non-recursive). */
    suspend fun list(): List<ImportFileEntry>

    /** Lists the immediate subfolders of the bound directory (for tree traversal when adding). */
    suspend fun listSubfolders(): List<ImportSubfolder>

    /** Downloads the raw bytes of the file identified by [fileRef] (from [ImportFileEntry.ref]). */
    suspend fun download(fileRef: String): ByteArray
}

/** Resolves an [ImportFileSource] for a configured [ImportDirectory] (local folder or Drive folder). */
interface ImportFileSourceFactory {
    suspend fun create(directory: ImportDirectory): ImportFileSource
}

/** A folder the user can pick as a Google Drive import directory. */
data class ImportFolder(
    val id: String,
    val name: String,
)

/**
 * Browses the user's Google Drive folder hierarchy for the add-directory folder picker, one level at a
 * time. Signs in (requesting the read-only Drive scope) if needed. [providerConfig] is the OAuth client
 * config, or null to use the app's shipped default credentials.
 */
interface DriveFolderBrowser {
    /** The id of the My Drive root, used as the starting parent for the "My Drive" root. */
    val rootFolderId: String

    /** Sentinel parent id for the "Shared with me" root (items shared to the user). */
    val sharedWithMeFolderId: String

    /** Lists the immediate subfolders of [parentId] ([rootFolderId] / [sharedWithMeFolderId] = roots). */
    suspend fun listChildFolders(
        providerConfig: String?,
        parentId: String,
    ): List<ImportFolder>
}

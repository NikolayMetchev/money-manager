package com.moneymanager.domain.repository

import com.moneymanager.domain.model.ImportDirectoryId
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.importdirectory.ImportDirectoryFile
import kotlinx.coroutines.flow.Flow

interface ImportDirectoryReadRepository {
    /** Gets all import directories, ordered by name. */
    fun getAllDirectories(): Flow<List<ImportDirectory>>

    /** Gets a single import directory by ID. */
    fun getDirectoryById(id: ImportDirectoryId): Flow<ImportDirectory?>

    /** Gets the per-file change-detection cursors tracked for a directory. */
    fun getTrackedFiles(id: ImportDirectoryId): Flow<List<ImportDirectoryFile>>

    /** Gets a single tracked file by its (directory, fileRef) key, or null if never imported. */
    suspend fun getTrackedFile(
        id: ImportDirectoryId,
        fileRef: String,
    ): ImportDirectoryFile?
}

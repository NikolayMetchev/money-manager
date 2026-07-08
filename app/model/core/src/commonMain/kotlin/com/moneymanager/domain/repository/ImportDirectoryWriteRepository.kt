@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.repository

import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.importdirectory.ImportDirectoryId
import com.moneymanager.domain.model.qif.QifImportId
import kotlin.time.Instant

interface ImportDirectoryWriteRepository : ImportDirectoryReadRepository {
    /**
     * Creates a new import directory.
     *
     * @param directory The directory to create
     * @param source Provenance recorded for the new directory revision
     * @return The ID of the created directory
     */
    suspend fun createDirectory(
        directory: ImportDirectory,
        source: Source,
    ): ImportDirectoryId

    /**
     * Updates an existing import directory. The updatedAt timestamp is set automatically.
     *
     * @param directory The directory with updated values
     * @param source Provenance recorded for the new directory revision
     */
    suspend fun updateDirectory(
        directory: ImportDirectory,
        source: Source,
    )

    /** Deletes an import directory (cascades to its tracked files). */
    suspend fun deleteDirectory(id: ImportDirectoryId)

    /**
     * Records (or advances) the change-detection cursor for a file just imported from a directory.
     * Exactly one of [csvImportId] / [qifImportId] is set, matching the directory's import type.
     */
    suspend fun recordFileImported(
        directoryId: ImportDirectoryId,
        fileRef: String,
        fileName: String,
        lastModified: Instant,
        checksum: String?,
        remoteContentHash: String?,
        csvImportId: CsvImportId?,
        qifImportId: QifImportId?,
        importedAt: Instant,
    )
}

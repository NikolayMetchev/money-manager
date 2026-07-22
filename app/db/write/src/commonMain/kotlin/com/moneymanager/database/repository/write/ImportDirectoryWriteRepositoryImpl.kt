@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository.write

import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.ImportDirectoryId
import com.moneymanager.domain.model.QifImportId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.toSourceType
import com.moneymanager.domain.repository.ImportDirectoryReadRepository
import com.moneymanager.domain.repository.write.ImportDirectoryWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Instant

class ImportDirectoryWriteRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
    private val deviceId: DeviceId,
    reader: ImportDirectoryReadRepository,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : ImportDirectoryWriteRepository,
    ImportDirectoryReadRepository by reader {
    private val selectQueries = database.importDirectorySelectQueries
    private val writeQueries = database.importDirectoryWriteQueries

    override suspend fun createDirectory(
        directory: ImportDirectory,
        source: Source,
    ): ImportDirectoryId =
        withContext(coroutineContext) {
            database.transaction {
                writeQueries.insert(
                    id = directory.id.id.toString(),
                    name = directory.name,
                    provider_type_id = directory.provider.id.toLong(),
                    folder_ref = directory.folderRef,
                    folder_display_path = directory.displayPath,
                    provider_config = directory.providerConfig,
                    device_id = directory.deviceId?.id,
                    top_level = if (directory.topLevel) 1L else 0L,
                    parent_id = directory.parentId?.id?.toString(),
                    excluded = if (directory.excluded) 1L else 0L,
                    account_id = directory.accountId?.id,
                )
                writeQueries.insertSource(
                    directory_id = directory.id.id.toString(),
                    revision_id = 1,
                    source_type_id = source.toSourceType().id.toLong(),
                    device_id = deviceId.id,
                )
            }
            directory.id
        }

    override suspend fun updateDirectory(
        directory: ImportDirectory,
        source: Source,
    ): Unit =
        withContext(coroutineContext) {
            val now = Clock.System.now()
            // One transaction so the revision_id read back reflects exactly this update.
            database.transaction {
                writeQueries.update(
                    name = directory.name,
                    provider_type_id = directory.provider.id.toLong(),
                    folder_ref = directory.folderRef,
                    folder_display_path = directory.displayPath,
                    provider_config = directory.providerConfig,
                    device_id = directory.deviceId?.id,
                    top_level = if (directory.topLevel) 1L else 0L,
                    parent_id = directory.parentId?.id?.toString(),
                    excluded = if (directory.excluded) 1L else 0L,
                    account_id = directory.accountId?.id,
                    updated_at = now.toEpochMilliseconds(),
                    id = directory.id.id.toString(),
                )
                val persistedRevisionId =
                    selectQueries.selectById(directory.id.id.toString()).executeAsOne().revision_id
                writeQueries.insertSource(
                    directory_id = directory.id.id.toString(),
                    revision_id = persistedRevisionId,
                    source_type_id = source.toSourceType().id.toLong(),
                    device_id = deviceId.id,
                )
            }
        }

    override suspend fun deleteDirectory(id: ImportDirectoryId): Unit =
        withContext(coroutineContext) {
            writeQueries.deleteById(id.id.toString())
        }

    override suspend fun recordFileImported(
        directoryId: ImportDirectoryId,
        fileRef: String,
        fileName: String,
        lastModified: Instant,
        checksum: String?,
        remoteContentHash: String?,
        csvImportId: CsvImportId?,
        qifImportId: QifImportId?,
        importedAt: Instant,
    ): Unit =
        withContext(coroutineContext) {
            // A tracked file stages into at most one import type (CSV xor QIF); reject mixed state
            // before any write. The schema also enforces this via the file's sibling-table triggers.
            require(csvImportId == null || qifImportId == null) {
                "A directory file resolves to at most one import type, but both csvImportId=$csvImportId " +
                    "and qifImportId=$qifImportId were supplied for $fileRef"
            }
            database.transaction {
                writeQueries.upsertFile(
                    directory_id = directoryId.id.toString(),
                    file_ref = fileRef,
                    file_name = fileName,
                    last_modified = lastModified.toEpochMilliseconds(),
                    checksum = checksum,
                    remote_content_hash = remoteContentHash,
                    imported_at = importedAt.toEpochMilliseconds(),
                )
                // The csv/qif import lives in a 1:1 child table; replace it so a re-import (or a
                // file that no longer stages anything) leaves exactly the right child row.
                val fileId = selectQueries.selectFileByRef(directoryId.id.toString(), fileRef).executeAsOne().id
                writeQueries.deleteCsvImportForFile(fileId)
                writeQueries.deleteQifImportForFile(fileId)
                csvImportId?.let { writeQueries.insertCsvImportForFile(fileId, it.id.toString()) }
                qifImportId?.let { writeQueries.insertQifImportForFile(fileId, it.id.toString()) }
            }
        }
}

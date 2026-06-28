@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.importdirectory.ImportDirectoryId
import com.moneymanager.domain.model.qif.QifImportId
import com.moneymanager.domain.model.toSourceType
import com.moneymanager.domain.repository.ImportDirectoryReadRepository
import com.moneymanager.domain.repository.ImportDirectoryWriteRepository
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
                    provider_type = directory.provider.name,
                    folder_ref = directory.folderRef,
                    folder_display_path = directory.displayPath,
                    provider_config = directory.providerConfig,
                    device_id = directory.deviceId?.id,
                    top_level = if (directory.topLevel) 1L else 0L,
                    parent_id = directory.parentId?.id?.toString(),
                    excluded = if (directory.excluded) 1L else 0L,
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
                    provider_type = directory.provider.name,
                    folder_ref = directory.folderRef,
                    folder_display_path = directory.displayPath,
                    provider_config = directory.providerConfig,
                    device_id = directory.deviceId?.id,
                    top_level = if (directory.topLevel) 1L else 0L,
                    parent_id = directory.parentId?.id?.toString(),
                    excluded = if (directory.excluded) 1L else 0L,
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
        csvImportId: CsvImportId?,
        qifImportId: QifImportId?,
        importedAt: Instant,
    ): Unit =
        withContext(coroutineContext) {
            writeQueries.upsertFile(
                directory_id = directoryId.id.toString(),
                file_ref = fileRef,
                file_name = fileName,
                last_modified = lastModified.toEpochMilliseconds(),
                checksum = checksum,
                csv_import_id = csvImportId?.id?.toString(),
                qif_import_id = qifImportId?.id?.toString(),
                imported_at = importedAt.toEpochMilliseconds(),
            )
        }
}

@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.sql.importDirectory.Import_directory
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.importdirectory.ImportDirectoryFile
import com.moneymanager.domain.model.importdirectory.ImportDirectoryId
import com.moneymanager.domain.model.importdirectory.ImportDirectoryProvider
import com.moneymanager.domain.model.qif.QifImportId
import com.moneymanager.domain.repository.ImportDirectoryReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ImportDirectoryReadRepositoryImpl(
    database: MoneyManagerDatabase,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : ImportDirectoryReadRepository {
    private val selectQueries = database.importDirectorySelectQueries

    override fun getAllDirectories(): Flow<List<ImportDirectory>> =
        selectQueries
            .selectAll()
            .asFlow()
            .mapToList(coroutineContext)
            .map { rows -> rows.map(::toDomain) }

    override fun getDirectoryById(id: ImportDirectoryId): Flow<ImportDirectory?> =
        selectQueries
            .selectById(id.id.toString())
            .asFlow()
            .mapToOneOrNull(coroutineContext)
            .map { it?.let(::toDomain) }

    override fun getTrackedFiles(id: ImportDirectoryId): Flow<List<ImportDirectoryFile>> =
        selectQueries
            .selectFilesForDirectory(id.id.toString())
            .asFlow()
            .mapToList(coroutineContext)
            .map { rows ->
                rows.map {
                    toFileDomain(
                        it.directory_id,
                        it.file_name,
                        it.file_ref,
                        it.last_modified,
                        it.checksum,
                        it.csv_import_id,
                        it.qif_import_id,
                        it.imported_at,
                    )
                }
            }

    override suspend fun getTrackedFile(
        id: ImportDirectoryId,
        fileRef: String,
    ): ImportDirectoryFile? =
        withContext(coroutineContext) {
            selectQueries
                .selectFileByRef(id.id.toString(), fileRef)
                .executeAsOneOrNull()
                ?.let {
                    toFileDomain(
                        it.directory_id,
                        it.file_name,
                        it.file_ref,
                        it.last_modified,
                        it.checksum,
                        it.csv_import_id,
                        it.qif_import_id,
                        it.imported_at,
                    )
                }
        }

    private fun toDomain(entity: Import_directory): ImportDirectory =
        ImportDirectory(
            id = ImportDirectoryId(Uuid.parse(entity.id)),
            name = entity.name,
            provider = ImportDirectoryProvider.fromId(entity.provider_type_id),
            folderRef = entity.folder_ref,
            displayPath = entity.folder_display_path,
            providerConfig = entity.provider_config,
            deviceId = entity.device_id?.let(::DeviceId),
            topLevel = entity.top_level != 0L,
            parentId = entity.parent_id?.let { ImportDirectoryId(Uuid.parse(it)) },
            excluded = entity.excluded != 0L,
            createdAt = Instant.fromEpochMilliseconds(entity.created_at),
            updatedAt = Instant.fromEpochMilliseconds(entity.updated_at),
        )

    @Suppress("LongParameterList")
    private fun toFileDomain(
        directoryId: String,
        fileName: String,
        fileRef: String,
        lastModified: Long,
        checksum: String?,
        csvImportId: String?,
        qifImportId: String?,
        importedAt: Long?,
    ): ImportDirectoryFile =
        ImportDirectoryFile(
            directoryId = ImportDirectoryId(Uuid.parse(directoryId)),
            fileRef = fileRef,
            fileName = fileName,
            lastModified = Instant.fromEpochMilliseconds(lastModified),
            checksum = checksum,
            csvImportId = csvImportId?.let { CsvImportId(Uuid.parse(it)) },
            qifImportId = qifImportId?.let { QifImportId(Uuid.parse(it)) },
            importedAt = importedAt?.let(Instant::fromEpochMilliseconds),
        )
}

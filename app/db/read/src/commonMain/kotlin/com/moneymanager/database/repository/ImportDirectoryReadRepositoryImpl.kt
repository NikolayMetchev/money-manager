@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.sql.importDirectory.Import_directory
import com.moneymanager.database.sql.importDirectory.Import_directory_file
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
            .map { rows -> rows.map(::toFileDomain) }

    override suspend fun getTrackedFile(
        id: ImportDirectoryId,
        fileRef: String,
    ): ImportDirectoryFile? =
        withContext(coroutineContext) {
            selectQueries
                .selectFileByRef(id.id.toString(), fileRef)
                .executeAsOneOrNull()
                ?.let(::toFileDomain)
        }

    private fun toDomain(entity: Import_directory): ImportDirectory =
        ImportDirectory(
            id = ImportDirectoryId(Uuid.parse(entity.id)),
            name = entity.name,
            provider = ImportDirectoryProvider.valueOf(entity.provider_type),
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

    private fun toFileDomain(entity: Import_directory_file): ImportDirectoryFile =
        ImportDirectoryFile(
            directoryId = ImportDirectoryId(Uuid.parse(entity.directory_id)),
            fileRef = entity.file_ref,
            fileName = entity.file_name,
            lastModified = Instant.fromEpochMilliseconds(entity.last_modified),
            checksum = entity.checksum,
            csvImportId = entity.csv_import_id?.let { CsvImportId(Uuid.parse(it)) },
            qifImportId = entity.qif_import_id?.let { QifImportId(Uuid.parse(it)) },
            importedAt = entity.imported_at?.let(Instant::fromEpochMilliseconds),
        )
}

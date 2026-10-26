package com.moneymanager.database.repository

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.sql.importDirectory.Import_directory
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.ImportDirectoryId
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.importdirectory.ImportDirectoryFile
import com.moneymanager.domain.model.importdirectory.ImportDirectoryProvider
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
                        it.remote_content_hash,
                        it.csv_import_id,
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
                .awaitAsOneOrNull()
                ?.let {
                    toFileDomain(
                        it.directory_id,
                        it.file_name,
                        it.file_ref,
                        it.last_modified,
                        it.checksum,
                        it.remote_content_hash,
                        it.csv_import_id,
                        it.imported_at,
                    )
                }
        }

    override suspend fun getTrackedFilesForCsvImport(id: CsvImportId): List<ImportDirectoryFile> =
        withContext(coroutineContext) {
            selectQueries.selectFilesForCsvImport(id.id.toString()).awaitAsList().map {
                toFileDomain(
                    it.directory_id,
                    it.file_name,
                    it.file_ref,
                    it.last_modified,
                    it.checksum,
                    it.remote_content_hash,
                    it.csv_import_id,
                    it.imported_at,
                )
            }
        }

    override suspend fun csvImportSourceAccounts(): Map<CsvImportId, AccountId> =
        withContext(coroutineContext) {
            selectQueries
                .selectStagedImportAccounts()
                .awaitAsList()
                .mapNotNull { row ->
                    val accountId = row.account_id ?: return@mapNotNull null
                    CsvImportId(Uuid.parse(row.csv_import_id)) to AccountId(accountId)
                }.toUnambiguousMap()
        }

    override suspend fun csvImportFoldersWithoutAccount(): Map<CsvImportId, ImportDirectoryId> =
        withContext(coroutineContext) {
            selectQueries
                .selectStagedImportFoldersWithoutAccount()
                .awaitAsList()
                .map { row -> CsvImportId(Uuid.parse(row.csv_import_id)) to ImportDirectoryId(Uuid.parse(row.directory_id)) }
                .toUnambiguousMap()
        }

    override fun csvImportDirectories(): Flow<Map<CsvImportId, List<ImportDirectoryId>>> =
        selectQueries
            .selectCsvImportDirectories()
            .asFlow()
            .mapToList(coroutineContext)
            .map { rows -> rows.groupDirectories({ CsvImportId(Uuid.parse(it.csv_import_id)) }, { it.directory_id }) }

    private fun <R, K> List<R>.groupDirectories(
        importId: (R) -> K,
        directoryId: (R) -> String,
    ): Map<K, List<ImportDirectoryId>> =
        groupBy(importId) { ImportDirectoryId(Uuid.parse(directoryId(it))) }.mapValues { (_, ids) -> ids.distinct() }

    /**
     * A staged import can be reused (by content checksum) across more than one directory, so the same
     * import id can appear here paired with a DIFFERENT directory's account (or folder). Collapsing that
     * with a plain `toMap()` would pick an arbitrary, order-dependent one. Instead, keep an id only when
     * every row for it agrees; an id with conflicting values is dropped rather than resolved to a
     * possibly-wrong one, so its caller falls back to its own default (a shared override, or prompting).
     */
    private fun <K, V> List<Pair<K, V>>.toUnambiguousMap(): Map<K, V> =
        groupBy({ it.first }, { it.second })
            .mapNotNull { (id, values) -> values.toSet().singleOrNull()?.let { id to it } }
            .toMap()

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
            accountId = entity.account_id?.let(::AccountId),
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
        remoteContentHash: String?,
        csvImportId: String?,
        importedAt: Long?,
    ): ImportDirectoryFile =
        ImportDirectoryFile(
            directoryId = ImportDirectoryId(Uuid.parse(directoryId)),
            fileRef = fileRef,
            fileName = fileName,
            lastModified = Instant.fromEpochMilliseconds(lastModified),
            checksum = checksum,
            remoteContentHash = remoteContentHash,
            csvImportId = csvImportId?.let { CsvImportId(Uuid.parse(it)) },
            importedAt = importedAt?.let(Instant::fromEpochMilliseconds),
        )
}

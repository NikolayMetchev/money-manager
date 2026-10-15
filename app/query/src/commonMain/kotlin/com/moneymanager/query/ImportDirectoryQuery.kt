package com.moneymanager.query

import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.importdirectory.ImportDirectoryFile
import com.moneymanager.domain.repository.ImportDirectoryReadRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

/** The configured import directories with what their files have done so far, as [device] sees them. */
class ImportDirectoryQuery(
    private val importDirectoryRepository: ImportDirectoryReadRepository,
    private val device: DeviceId,
) {
    /** Top-level folders by name, each followed by its discovered subfolders by name. */
    suspend fun directories(): List<ImportDirectoryStatus> {
        val all = importDirectoryRepository.getAllDirectories().first()
        val children = all.filter { it.parentId != null }.groupBy { it.parentId }
        val topLevel = all.filter { it.parentId == null }.sortedBy { it.name.lowercase() }
        val placed = topLevel.flatMap { listOf(it) + children[it.id].orEmpty().sortedBy { child -> child.name.lowercase() } }
        // A subfolder whose parent is gone still shows, last.
        val ordered = placed + (all - placed.toSet()).sortedBy { it.name.lowercase() }
        return ordered.map { ImportDirectoryStatus(it, importDirectoryRepository.getTrackedFiles(it.id).first(), it.isOn(device)) }
    }
}

data class ImportDirectoryStatus(
    val directory: ImportDirectory,
    val trackedFiles: List<ImportDirectoryFile>,
    /** False for a LOCAL folder another device added. */
    val onThisDevice: Boolean,
)

@Serializable
data class ImportDirectoryJson(
    /** A UUID. */
    val id: String,
    val name: String,
    /** `local` or `gdrive`. */
    val provider: String,
    /** The folder as a person would recognise it (a filesystem path, or a Drive path). */
    val path: String,
    val topLevel: Boolean,
    /** The top-level folder a discovered subfolder belongs to. */
    val parentId: String?,
    /** Excluded folders are skipped by "download all". */
    val excluded: Boolean,
    /** The account files from this folder are imported into, when it sets one. */
    val accountId: Long?,
    /** False for a local folder on another device, which can't be downloaded here. */
    val onThisDevice: Boolean,
    /** Files downloaded from the folder so far. */
    val trackedFiles: Int,
    /** Of [trackedFiles], those imported. */
    val importedFiles: Int,
    /** ISO-8601 instant of the latest import, or null if none was. */
    val lastImportedAt: String?,
)

fun ImportDirectoryStatus.toJson(): ImportDirectoryJson =
    ImportDirectoryJson(
        id = directory.id.id.toString(),
        name = directory.name,
        provider = directory.provider.name.lowercase(),
        path = directory.displayPath ?: directory.folderRef,
        topLevel = directory.topLevel,
        parentId = directory.parentId?.id?.toString(),
        excluded = directory.excluded,
        accountId = directory.accountId?.id,
        onThisDevice = onThisDevice,
        trackedFiles = trackedFiles.size,
        importedFiles = trackedFiles.count { it.importedAt != null },
        lastImportedAt = trackedFiles.mapNotNull { it.importedAt }.maxOrNull()?.toString(),
    )

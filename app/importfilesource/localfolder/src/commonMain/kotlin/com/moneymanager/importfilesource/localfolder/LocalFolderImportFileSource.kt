package com.moneymanager.importfilesource.localfolder

import com.moneymanager.files.absolutePath
import com.moneymanager.files.expandTilde
import com.moneymanager.files.lastModifiedEpochMs
import com.moneymanager.files.readBytes
import com.moneymanager.importfilesource.ImportFileEntry
import com.moneymanager.importfilesource.ImportFileSource
import com.moneymanager.importfilesource.ImportSubfolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.FileMetadata
import kotlinx.io.files.FileNotFoundException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Lists and reads files from a filesystem folder. [folderPath] is an absolute path; on Android it must
 * be a path the app can read (e.g. app-scoped or granted storage). Tilde (`~`) is expanded to the user's
 * home so configured paths can be portable on desktop.
 *
 * The file ref is the file's name within the folder (folders are flat, non-recursive), which keeps the
 * change-detection cursor stable as long as the file keeps its name.
 */
class LocalFolderImportFileSource(
    folderPath: String,
) : ImportFileSource {
    private val folder = Path(expandTilde(folderPath))

    override suspend fun list(): List<ImportFileEntry> =
        withContext(Dispatchers.IO) {
            requireListing()
                .filter { (_, metadata) -> metadata.isRegularFile }
                .map { (file, metadata) ->
                    ImportFileEntry(
                        ref = file.name,
                        name = file.name,
                        lastModifiedEpochMs = lastModifiedEpochMs(file),
                        sizeBytes = metadata.size,
                    )
                }.sortedBy { it.name }
        }

    override suspend fun listSubfolders(): List<ImportSubfolder> =
        withContext(Dispatchers.IO) {
            requireListing()
                .filter { (_, metadata) -> metadata.isDirectory }
                .map { (dir, _) -> ImportSubfolder(ref = absolutePath(dir).toString(), name = dir.name) }
                .sortedBy { it.name }
        }

    override suspend fun download(fileRef: String): ByteArray =
        withContext(Dispatchers.IO) {
            readBytes(Path(folder, fileRef))
        }

    // A missing path, a non-directory or a permission failure must surface as an error rather than
    // masquerade as an empty (and silently skipped) folder.
    private fun requireListing(): List<Pair<Path, FileMetadata>> {
        val problem =
            when (SystemFileSystem.metadataOrNull(folder)?.isDirectory) {
                null -> "does not exist"
                false -> "is not a folder"
                true -> null
            }
        if (problem != null) throw FileNotFoundException("Import directory $problem: $folder")
        val children =
            try {
                SystemFileSystem.list(folder)
            } catch (e: IOException) {
                throw IOException("Cannot read import directory (permission denied?): $folder", e)
            }
        // An entry that vanishes between listing and stat is skipped, like listFiles() did.
        return children.mapNotNull { child -> SystemFileSystem.metadataOrNull(child)?.let { child to it } }
    }
}

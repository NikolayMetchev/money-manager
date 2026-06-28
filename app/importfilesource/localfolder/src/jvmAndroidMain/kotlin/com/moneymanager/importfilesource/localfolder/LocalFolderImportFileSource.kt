package com.moneymanager.importfilesource.localfolder

import com.moneymanager.importfilesource.ImportFileEntry
import com.moneymanager.importfilesource.ImportFileSource
import com.moneymanager.importfilesource.ImportSubfolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Lists and reads files from a filesystem folder. [folderPath] is an absolute path; on JVM it is a
 * normal path, on Android it must be a path the app can read (e.g. app-scoped or granted storage).
 * Tilde (`~`) is expanded to the user's home so configured paths can be portable on desktop.
 *
 * The file ref is the file's name within the folder (folders are flat, non-recursive), which keeps the
 * change-detection cursor stable as long as the file keeps its name.
 */
class LocalFolderImportFileSource(
    folderPath: String,
) : ImportFileSource {
    private val folder: File = File(expandTilde(folderPath))

    override suspend fun list(): List<ImportFileEntry> =
        withContext(Dispatchers.IO) {
            requireListing()
                .filter { it.isFile }
                .map { file ->
                    ImportFileEntry(
                        ref = file.name,
                        name = file.name,
                        lastModifiedEpochMs = file.lastModified(),
                        sizeBytes = file.length(),
                    )
                }.sortedBy { it.name }
        }

    override suspend fun listSubfolders(): List<ImportSubfolder> =
        withContext(Dispatchers.IO) {
            requireListing()
                .filter { it.isDirectory }
                .map { ImportSubfolder(ref = it.absolutePath, name = it.name) }
                .sortedBy { it.name }
        }

    override suspend fun download(fileRef: String): ByteArray =
        withContext(Dispatchers.IO) {
            File(folder, fileRef).readBytes()
        }

    // listFiles() returns null for a missing path, a non-directory, or a permission failure — all of
    // which must surface as an error rather than masquerade as an empty (and silently skipped) folder.
    private fun requireListing(): Array<File> {
        val problem =
            when {
                !folder.exists() -> "does not exist"
                !folder.isDirectory -> "is not a folder"
                else -> null
            }
        if (problem != null) throw FileNotFoundException("Import directory $problem: $folder")
        return folder.listFiles() ?: throw IOException("Cannot read import directory (permission denied?): $folder")
    }

    private companion object {
        fun expandTilde(path: String): String {
            val home = System.getProperty("user.home")
            return if (home != null && (path == "~" || path.startsWith("~/"))) {
                home + path.removePrefix("~")
            } else {
                path
            }
        }
    }
}

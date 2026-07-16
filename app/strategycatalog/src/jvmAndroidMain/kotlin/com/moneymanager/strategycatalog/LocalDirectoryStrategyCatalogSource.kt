package com.moneymanager.strategycatalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

/**
 * Reads the catalog's `index.json` manifest and per-artifact JSON files from a local filesystem
 * directory instead of the published GitHub Pages site — the same layout `:tools:strategy-catalog`
 * writes to `webpage/strategy-library/`, so a developer can point at that folder straight after
 * regenerating it, without publishing first. [directoryPath] is an absolute path; tilde (`~`) is
 * expanded to the user's home so a configured path is portable on desktop.
 */
class LocalDirectoryStrategyCatalogSource(
    directoryPath: String,
) : StrategyCatalogSource {
    private val directory: File = File(expandTilde(directoryPath))

    override suspend fun fetchManifest(): CatalogManifest = CatalogManifestCodec.decode(readFile("index.json"))

    override suspend fun fetchArtifact(fileName: String): String = readFile(fileName)

    private suspend fun readFile(fileName: String): String =
        withContext(Dispatchers.IO) {
            val problem =
                when {
                    !directory.exists() -> "does not exist"
                    !directory.isDirectory -> "is not a folder"
                    else -> null
                }
            if (problem != null) throw FileNotFoundException("Strategy catalog directory $problem: $directory")
            val file = File(directory, fileName)
            if (!file.exists()) throw FileNotFoundException("Catalog file not found: $file")
            file.readText()
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

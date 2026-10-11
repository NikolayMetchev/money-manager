package com.moneymanager.strategycatalog

import com.moneymanager.files.expandTilde
import com.moneymanager.files.isRegularFile
import com.moneymanager.files.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.FileNotFoundException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

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
    private val directory = Path(expandTilde(directoryPath))

    override suspend fun fetchManifest(): CatalogManifest = CatalogManifestCodec.decode(readFile("index.json"))

    override suspend fun fetchArtifact(fileName: String): String = readFile(fileName)

    private suspend fun readFile(fileName: String): String =
        withContext(Dispatchers.IO) {
            val problem =
                when (SystemFileSystem.metadataOrNull(directory)?.isDirectory) {
                    null -> "does not exist"
                    false -> "is not a folder"
                    true -> null
                }
            if (problem != null) throw FileNotFoundException("Strategy catalog directory $problem: $directory")
            val file = Path(directory, fileName)
            if (!isRegularFile(file)) throw FileNotFoundException("Catalog file not found: $file")
            readText(file)
        }
}

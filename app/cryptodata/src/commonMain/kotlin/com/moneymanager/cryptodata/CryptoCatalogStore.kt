package com.moneymanager.cryptodata

import com.moneymanager.files.isRegularFile
import com.moneymanager.files.readText
import com.moneymanager.files.writeText
import kotlinx.io.files.Path

// Persistence for the network-refreshed crypto catalog, stored as a plain TSV file in the app data
// directory (outside any money-manager database — it is external, non-user, non-sync reference data, so
// it must not go through the DB write seam). The bundled catalog is always available regardless; this
// only holds the optional refreshed layer.

/** Where the refreshed catalog is stored, or null when the platform has no location for it yet. */
internal expect fun cryptoCatalogStoreFile(): Path?

/** Reads the persisted refreshed catalog text, or null if none has been written / it isn't readable. */
fun readStoredCryptoCatalogText(): String? =
    cryptoCatalogStoreFile()?.takeIf(::isRegularFile)?.let {
        runCatching { readText(it) }.getOrNull()
    }

/** Writes the refreshed catalog [text] to the app data dir. Returns false if it couldn't be persisted. */
fun writeStoredCryptoCatalogText(text: String): Boolean {
    val file = cryptoCatalogStoreFile() ?: return false
    return runCatching { writeText(file, text) }.isSuccess
}

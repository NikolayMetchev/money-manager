package com.moneymanager.cryptodata

import java.io.File

/**
 * The app files directory, set once at startup (from `Context.filesDir`) via
 * [initializeCryptoCatalogStore]. Until set, the refreshed catalog can't be persisted on Android and
 * the bundled catalog is used — refresh still installs overrides for the running session.
 */
private var appFilesDir: File? = null

/** Initializes the Android store location. Call from the entry point before any refresh. */
fun initializeCryptoCatalogStore(filesDir: File) {
    appFilesDir = filesDir
}

private fun storeFile(): File? = appFilesDir?.resolve("crypto")?.resolve("coins.tsv")

actual fun readStoredCryptoCatalogText(): String? =
    storeFile()?.takeIf { it.isFile }?.let {
        runCatching { it.readText() }.getOrNull()
    }

actual fun writeStoredCryptoCatalogText(text: String): Boolean =
    runCatching {
        val file = storeFile() ?: return false
        file.parentFile?.mkdirs()
        file.writeText(text)
        true
    }.getOrDefault(false)

package com.moneymanager.cryptodata

import kotlinx.io.files.Path
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

internal actual fun cryptoCatalogStoreFile(): Path? = appFilesDir?.let { Path(it.path, "crypto", "coins.tsv") }

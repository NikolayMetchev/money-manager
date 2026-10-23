package com.moneymanager.cryptodata

import kotlinx.io.files.Path

/** The browser has no file system for a refreshed catalog, so it keeps the bundled one. */
internal actual fun cryptoCatalogStoreFile(): Path? = null

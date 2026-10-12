package com.moneymanager.cryptodata

import com.moneymanager.files.appDataDirectory
import kotlinx.io.files.Path

/** `~/.moneymanager/crypto/coins.tsv`: the same file as the desktop app, so a refresh in either is shared. */
internal actual fun cryptoCatalogStoreFile(): Path? = Path(appDataDirectory(), "crypto", "coins.tsv")

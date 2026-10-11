package com.moneymanager.cryptodata

import com.moneymanager.files.appDataDirectory
import kotlinx.io.files.Path

/** `~/.moneymanager/crypto/coins.tsv`, beside the default database and the remote DB cache. */
internal actual fun cryptoCatalogStoreFile(): Path? = Path(appDataDirectory(), "crypto", "coins.tsv")

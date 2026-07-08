package com.moneymanager.cryptodata

import java.io.File

/** `~/.moneymanager/crypto/coins.tsv` — mirrors the JVM app data dir used for the remote DB cache. */
private fun storeFile(): File = File(System.getProperty("user.home"), ".moneymanager").resolve("crypto").resolve("coins.tsv")

actual fun readStoredCryptoCatalogText(): String? =
    storeFile().takeIf { it.isFile }?.let {
        runCatching { it.readText() }.getOrNull()
    }

actual fun writeStoredCryptoCatalogText(text: String): Boolean =
    runCatching {
        val file = storeFile()
        file.parentFile?.mkdirs()
        file.writeText(text)
        true
    }.getOrDefault(false)

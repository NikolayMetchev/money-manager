package com.moneymanager.domain.model

import com.moneymanager.files.appDataDirectory
import kotlinx.io.files.Path

/** File extension for the compressed + encrypted database archive stored remotely. */
const val REMOTE_ARCHIVE_EXTENSION = "mmenc"

/** The local working-copy filename for a remote archive [name]: a sanitized base name with a `.db` extension. */
internal fun remoteCacheFileName(name: String): String {
    val sanitized = name.map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '_' }.joinToString("")
    val base = sanitized.substringBeforeLast('.', sanitized).ifBlank { "money_manager" }
    return "$base.db"
}

/** `~/.moneymanager/cloud/<name>.db`: the working copy on platforms where a location is a file path. */
internal fun fileRemoteCacheLocation(name: String): DbLocation =
    DbLocation(Path(appDataDirectory(), "cloud", remoteCacheFileName(name)).toString())

/** Suggests an archive name for a local database file [dbFileName] (e.g. `money_manager.db` → `money_manager.mmenc`). */
fun defaultRemoteArchiveName(dbFileName: String): String {
    val base = dbFileName.substringBeforeLast('.', dbFileName).ifBlank { "money_manager" }
    return "$base.$REMOTE_ARCHIVE_EXTENSION"
}

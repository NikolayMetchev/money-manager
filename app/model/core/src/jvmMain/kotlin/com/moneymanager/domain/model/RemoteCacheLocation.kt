package com.moneymanager.domain.model

import java.nio.file.Paths

actual fun remoteCacheLocation(name: String): DbLocation =
    DbLocation(Paths.get(System.getProperty("user.home"), ".moneymanager", "cloud", sanitizeFileName(name)))

private fun sanitizeFileName(name: String): String {
    val cleaned = name.map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '_' }.joinToString("")
    return cleaned.ifBlank { DEFAULT_DATABASE_NAME }
}

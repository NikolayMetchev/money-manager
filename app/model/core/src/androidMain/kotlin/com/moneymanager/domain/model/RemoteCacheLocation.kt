package com.moneymanager.domain.model

actual fun remoteCacheLocation(name: String): DbLocation = DbLocation("cloud_${sanitizeFileName(name)}")

private fun sanitizeFileName(name: String): String {
    val cleaned = name.map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '_' }.joinToString("")
    return cleaned.ifBlank { DEFAULT_DATABASE_NAME }
}

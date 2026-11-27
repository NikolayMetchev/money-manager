package com.moneymanager.csvimporter

import kotlinx.serialization.json.JsonPrimitive
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Path

// Android exposes unary SQLite functions, and API 30's SQLite does not include the JSON extension.
internal val sqliteProjectionFunctions: Map<String, (String) -> String> =
    mapOf("decimal_ratio" to ::decimalRatio, "sqlite_json_quote" to { JsonPrimitive(it).toString() })

private fun decimalRatio(ratio: String): String {
    val numerator = ratio.substringBefore('/').toBigDecimal()
    val denominator = ratio.substringAfter('/').toBigDecimal()
    return numerator.divide(denominator, 18, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString()
}

internal fun <T> withSqliteSnapshot(
    bytes: ByteArray,
    read: (Path) -> T,
): T {
    val directory = Files.createTempDirectory("mm-import-")
    val snapshot = directory.resolve("snapshot.sqlite")
    try {
        Files.write(snapshot, bytes)
        return read(snapshot)
    } finally {
        // Android's system SQLite may create WAL index files when opening a checkpointed WAL-mode snapshot.
        Files.newDirectoryStream(directory).use { entries -> entries.forEach(Files::deleteIfExists) }
        Files.deleteIfExists(directory)
    }
}

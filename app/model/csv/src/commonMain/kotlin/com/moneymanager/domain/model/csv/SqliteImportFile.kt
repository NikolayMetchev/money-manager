package com.moneymanager.domain.model.csv

/** The original database and the SELECT which produced its currently staged rows. */
class SqliteImportFile(
    val bytes: ByteArray,
    val stagedQuery: String?,
) {
    override fun equals(other: Any?): Boolean =
        other is SqliteImportFile && bytes.contentEquals(other.bytes) && stagedQuery == other.stagedQuery

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + stagedQuery.hashCode()
}

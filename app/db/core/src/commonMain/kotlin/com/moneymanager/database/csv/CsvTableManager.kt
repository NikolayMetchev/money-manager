@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.csv

import app.cash.sqldelight.db.QueryResult
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvRow
import kotlin.uuid.Uuid

/**
 * Manages dynamic CSV tables in the database.
 * These tables are created at runtime to store imported CSV data.
 */
class CsvTableManager(private val database: MoneyManagerDatabaseWrapper) {
    /**
     * Creates a new table to store CSV data.
     *
     * @param tableName The name of the table (should be csv_import_<uuid>)
     * @param columnCount The number of columns in the CSV
     */
    fun createCsvTable(
        tableName: String,
        columnCount: Int,
    ) {
        val columns = (0 until columnCount).joinToString(", ") { "col_$it TEXT" }
        database.execute(
            null,
            "CREATE TABLE IF NOT EXISTS $tableName (row_index INTEGER PRIMARY KEY AUTOINCREMENT, _transfer_id TEXT, $columns)",
            0,
        )
    }

    /**
     * Inserts rows into the CSV table in a batch.
     *
     * @param tableName The name of the table
     * @param rows The rows to insert
     * @param columnCount The number of columns
     */
    fun insertRowsBatch(
        tableName: String,
        rows: List<List<String>>,
        columnCount: Int,
    ) {
        if (rows.isEmpty()) return

        val columns = (0 until columnCount).joinToString(", ") { "col_$it" }
        val placeholders = (0 until columnCount).joinToString(", ") { "?" }
        val insertSql = "INSERT INTO $tableName ($columns) VALUES ($placeholders)"

        rows.forEach { row ->
            // Pad row if needed
            val paddedRow =
                if (row.size < columnCount) {
                    row + List(columnCount - row.size) { "" }
                } else {
                    row.take(columnCount)
                }

            database.executeWithParams(
                insertSql,
                columnCount,
                paddedRow,
            )
        }
    }

    /**
     * Queries rows from the CSV table with pagination.
     *
     * @param tableName The name of the table
     * @param columnCount The number of columns
     * @param limit Maximum number of rows to return
     * @param offset Number of rows to skip
     * @return List of CSV rows
     */
    fun queryRows(
        tableName: String,
        columnCount: Int,
        limit: Int,
        offset: Int,
    ): List<CsvRow> {
        val columns = (0 until columnCount).joinToString(", ") { "col_$it" }
        val sql = "SELECT row_index, _transfer_id, $columns FROM $tableName ORDER BY row_index LIMIT $limit OFFSET $offset"

        val result = mutableListOf<CsvRow>()
        database.executeQuery(
            null,
            sql,
            { cursor ->
                while (cursor.next().value) {
                    val rowIndex = cursor.getLong(0) ?: continue
                    val transferIdStr = cursor.getString(1)
                    val transferId = transferIdStr?.let { TransferId(Uuid.parse(it)) }
                    val values =
                        (0 until columnCount).map { i ->
                            cursor.getString(i + 2) ?: ""
                        }
                    result.add(CsvRow(rowIndex = rowIndex, values = values, transferId = transferId))
                }
                QueryResult.Unit
            },
            0,
        )
        return result
    }

    /**
     * Gets the total row count of a CSV table.
     *
     * @param tableName The name of the table
     * @return The number of rows
     */
    fun getRowCount(tableName: String): Long {
        var count = 0L
        database.executeQuery(
            null,
            "SELECT COUNT(*) FROM $tableName",
            { cursor ->
                if (cursor.next().value) {
                    count = cursor.getLong(0) ?: 0L
                }
                QueryResult.Unit
            },
            0,
        )
        return count
    }

    /**
     * Drops a CSV table.
     *
     * @param tableName The name of the table to drop
     */
    fun dropCsvTable(tableName: String) {
        database.execute(
            null,
            "DROP TABLE IF EXISTS $tableName",
            0,
        )
    }

    /**
     * Updates the transfer ID for a specific row.
     *
     * @param tableName The name of the table
     * @param rowIndex The row index to update
     * @param transferId The transfer ID to set
     */
    fun updateTransferId(
        tableName: String,
        rowIndex: Long,
        transferId: TransferId,
    ) {
        database.execute(
            null,
            "UPDATE $tableName SET _transfer_id = '${transferId.id}' WHERE row_index = $rowIndex",
            0,
        )
    }

    /**
     * Updates the transfer IDs for multiple rows in batch.
     *
     * @param tableName The name of the table
     * @param rowTransferMap Map of row index to transfer ID
     */
    fun updateTransferIdsBatch(
        tableName: String,
        rowTransferMap: Map<Long, TransferId>,
    ) {
        rowTransferMap.forEach { (rowIndex, transferId) ->
            updateTransferId(tableName, rowIndex, transferId)
        }
    }

    /**
     * Clears the transfer ID for a specific row.
     *
     * @param tableName The name of the table
     * @param rowIndex The row index to clear
     */
    fun clearTransferId(
        tableName: String,
        rowIndex: Long,
    ) {
        database.execute(
            null,
            "UPDATE $tableName SET _transfer_id = NULL WHERE row_index = $rowIndex",
            0,
        )
    }
}

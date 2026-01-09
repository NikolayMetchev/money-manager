@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.csv

import app.cash.sqldelight.db.QueryResult
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.ImportStatus

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
        val sql =
            "CREATE TABLE IF NOT EXISTS $tableName " +
                "(row_index INTEGER PRIMARY KEY AUTOINCREMENT, transaction_id TEXT, import_status TEXT, $columns)"
        database.execute(
            null,
            sql,
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
     * Queries rows from the CSV table with pagination, including any error messages.
     *
     * @param tableName The name of the table
     * @param csvImportId The CSV import ID for joining with error table
     * @param columnCount The number of columns
     * @param limit Maximum number of rows to return
     * @param offset Number of rows to skip
     * @return List of CSV rows
     */
    fun queryRows(
        tableName: String,
        csvImportId: String,
        columnCount: Int,
        limit: Int,
        offset: Int,
    ): List<CsvRow> {
        val columns = (0 until columnCount).joinToString(", ") { "t.col_$it" }
        val sql =
            """
            SELECT t.row_index, t.transaction_id, t.import_status, e.error_message, $columns
            FROM $tableName t
            LEFT JOIN csv_import_error e ON e.csv_import_id = '$csvImportId' AND e.row_index = t.row_index
            ORDER BY t.row_index
            LIMIT $limit OFFSET $offset
            """.trimIndent()

        val result = mutableListOf<CsvRow>()
        database.executeQuery(
            null,
            sql,
            { cursor ->
                while (cursor.next().value) {
                    val rowIndex = cursor.getLong(0) ?: continue
                    val transferIdLong = cursor.getLong(1)
                    val transferId = transferIdLong?.let { TransferId(it) }
                    val importStatusStr = cursor.getString(2)
                    val importStatus = importStatusStr?.let { ImportStatus.valueOf(it) }
                    val errorMessage = cursor.getString(3)
                    val values =
                        (0 until columnCount).map { i ->
                            cursor.getString(i + 4).orEmpty()
                        }
                    result.add(
                        CsvRow(
                            rowIndex = rowIndex,
                            values = values,
                            transferId = transferId,
                            importStatus = importStatus,
                            errorMessage = errorMessage,
                        ),
                    )
                }
                QueryResult.Unit
            },
            0,
        )
        return result
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
            "UPDATE $tableName SET transaction_id = '${transferId.id}' WHERE row_index = $rowIndex",
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
     * Updates the import status for a specific row.
     *
     * @param tableName The name of the table
     * @param rowIndex The row index to update
     * @param status The import status to set (IMPORTED, DUPLICATE, UPDATED)
     */
    fun updateImportStatus(
        tableName: String,
        rowIndex: Long,
        status: String,
    ) {
        database.execute(
            null,
            "UPDATE $tableName SET import_status = '$status' WHERE row_index = $rowIndex",
            0,
        )
    }

    /**
     * Updates the import status and transfer ID for a specific row.
     *
     * @param tableName The name of the table
     * @param rowIndex The row index to update
     * @param status The import status to set
     * @param transferId The transfer ID to set (optional)
     */
    fun updateRowStatus(
        tableName: String,
        rowIndex: Long,
        status: String,
        transferId: TransferId? = null,
    ) {
        val transferIdClause = transferId?.let { ", transaction_id = '${it.id}'" }.orEmpty()
        database.execute(
            null,
            "UPDATE $tableName SET import_status = '$status'$transferIdClause WHERE row_index = $rowIndex",
            0,
        )
    }
}

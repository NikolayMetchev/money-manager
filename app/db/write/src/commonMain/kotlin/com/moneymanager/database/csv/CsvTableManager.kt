package com.moneymanager.database.csv

import app.cash.sqldelight.async.coroutines.awaitAsList
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.ImportStatus

/**
 * Manages dynamic CSV tables in the database.
 * These tables are created at runtime to store imported CSV data.
 */
class CsvTableManager(
    private val database: MoneyManagerDatabaseWrapper,
) {
    /**
     * Creates a new table to store CSV data.
     *
     * @param tableName The name of the table (should be csv_import_<uuid>)
     * @param columnCount The number of columns in the CSV
     */
    suspend fun createCsvTable(
        tableName: String,
        columnCount: Int,
    ) {
        // A file that parses to 0 columns must not emit a trailing comma before ')' (invalid SQL).
        val columns = (0 until columnCount).joinToString("") { ", col_$it TEXT" }
        val sql =
            "CREATE TABLE IF NOT EXISTS $tableName " +
                "(row_index INTEGER PRIMARY KEY AUTOINCREMENT, transaction_id TEXT, import_status TEXT$columns)"
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
    suspend fun insertRowsBatch(
        tableName: String,
        rows: List<List<String>>,
        columnCount: Int,
    ) {
        if (rows.isEmpty()) return

        // A zero-column file has no col_ values; insert bare rows (row_index autoincrements) rather than
        // emitting the invalid `INSERT INTO t () VALUES ()`.
        if (columnCount == 0) {
            repeat(rows.size) { database.execute(null, "INSERT INTO $tableName DEFAULT VALUES", 0) }
            return
        }

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
    suspend fun queryRows(
        tableName: String,
        csvImportId: String,
        columnCount: Int,
        limit: Int,
        offset: Int,
    ): List<CsvRow> {
        val columns = (0 until columnCount).joinToString(", ") { "t.col_$it" }
        // A zero-column table has no col_ projection: avoid a trailing comma before FROM.
        val projectedColumns = if (columns.isEmpty()) "" else ",\n                $columns"
        val sql =
            """
            SELECT
                t.row_index,
                COALESCE(NULLIF(t.transaction_id, '0'), source_rows.transaction_id) AS transaction_id,
                t.import_status,
                e.error_message$projectedColumns
            FROM $tableName t
            LEFT JOIN csv_import_error e ON e.csv_import_id = '$csvImportId' AND e.row_index = t.row_index
            LEFT JOIN (
                SELECT
                    csv_entity_source.csv_import_id,
                    csv_entity_source.csv_row_index,
                    MIN(entity_source.entity_id) AS transaction_id
                FROM csv_entity_source
                JOIN entity_source ON entity_source.id = csv_entity_source.id
                -- entity_source outlives deleted transfers; without this a reset row would still
                -- report a transfer that a re-import rewrite or an unimport removed.
                JOIN transfer ON transfer.id = entity_source.entity_id
                WHERE csv_entity_source.csv_import_id = '$csvImportId'
                  AND entity_source.entity_type_id = 7
                GROUP BY csv_entity_source.csv_import_id, csv_entity_source.csv_row_index
            ) source_rows ON source_rows.csv_import_id = '$csvImportId'
                AND source_rows.csv_row_index = t.row_index
            ORDER BY t.row_index
            LIMIT $limit OFFSET $offset
            """.trimIndent()

        return database
            .rawQuery(sql) { cursor ->
                val importStatusStr = cursor.getString(2)
                CsvRow(
                    rowIndex = cursor.getLong(0)!!,
                    values = (0 until columnCount).map { i -> cursor.getString(i + 4).orEmpty() },
                    transferId = cursor.getLong(1)?.let { TransferId(it) },
                    importStatus = importStatusStr?.let { ImportStatus.valueOf(it) },
                    errorMessage = cursor.getString(3),
                )
            }.awaitAsList()
    }

    /**
     * Drops a CSV table.
     *
     * @param tableName The name of the table to drop
     */
    suspend fun dropCsvTable(tableName: String) {
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
    suspend fun updateTransferId(
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
    suspend fun updateTransferIdsBatch(
        tableName: String,
        rowTransferMap: Map<Long, TransferId>,
    ) {
        rowTransferMap.forEach { (rowIndex, transferId) ->
            updateTransferId(tableName, rowIndex, transferId)
        }
    }

    /**
     * Updates the import status and transfer ID for a specific row.
     *
     * @param tableName The name of the table
     * @param rowIndex The row index to update
     * @param status The import status to set
     * @param transferId The transfer ID to set (optional)
     */
    suspend fun updateRowStatus(
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

    /**
     * Clears the import status + transfer link for the given rows, returning them to the
     * never-imported state so a strategy re-run picks them up again.
     *
     * @param tableName The name of the table
     * @param rowIndexes The row indexes to reset
     */
    suspend fun resetRowStatuses(
        tableName: String,
        rowIndexes: Collection<Long>,
    ) {
        if (rowIndexes.isEmpty()) return
        database.execute(
            null,
            "UPDATE $tableName SET import_status = NULL, transaction_id = NULL " +
                "WHERE row_index IN (${rowIndexes.joinToString(",")})",
            0,
        )
    }

    /** Returns every row of [tableName] to the never-imported state (see [resetRowStatuses]). */
    suspend fun resetAllRowStatuses(tableName: String) {
        database.execute(null, "UPDATE $tableName SET import_status = NULL, transaction_id = NULL", 0)
    }

    /**
     * The rows of [tableName] whose linked transaction (transfer or trade — they share one id space) is
     * one of [transactionIds]. The column is TEXT but may hold the id in either storage class, hence the
     * cast.
     */
    suspend fun findRowsWithTransactionIds(
        tableName: String,
        transactionIds: Collection<Long>,
    ): List<Long> {
        val rows =
            transactionIds.chunked(ID_CHUNK_SIZE).flatMap { chunk ->
                database
                    .rawQuery(
                        "SELECT row_index FROM $tableName " +
                            "WHERE CAST(transaction_id AS INTEGER) IN (${chunk.joinToString(",")}) ORDER BY row_index",
                    ) { it.getLong(0)!! }
                    .awaitAsList()
            }
        return rows.distinct().sorted()
    }

    private companion object {
        const val ID_CHUNK_SIZE = 500
    }
}

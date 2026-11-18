package com.moneymanager.database.csv

import app.cash.sqldelight.async.coroutines.awaitAsList
import com.moneymanager.database.rawsql.RawSql
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.ImportStatus

/**
 * Manages dynamic CSV tables in the database.
 * These tables are created at runtime to store imported CSV data, one per import with as many columns as the
 * file has, so their SQL can't be a SQLDelight query: this is a sanctioned user of [RawSql].
 */
class CsvTableManager(
    database: MoneyManagerDatabaseWrapper,
) {
    private val database = RawSql(database.driver)

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
                "(row_index INTEGER PRIMARY KEY AUTOINCREMENT, import_status TEXT$columns)"
        database.execute(sql)
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
            repeat(rows.size) { database.execute("INSERT INTO $tableName DEFAULT VALUES") }
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

            database.executeWithStrings(insertSql, paddedRow)
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
                source_rows.transaction_id,
                t.import_status,
                e.error_message$projectedColumns
            FROM $tableName t
            LEFT JOIN csv_import_error e ON e.csv_import_id = '$csvImportId' AND e.row_index = t.row_index
            LEFT JOIN (
                -- A row's transaction is what its observations joined: those its record owns, and those of a group
                -- (a trade's legs) it belongs to. Its own movement (or trade) speaks for it before its fee or chain.
                SELECT row_index, transaction_id
                FROM (
                    SELECT
                        owned.row_index,
                        observation.transaction_id,
                        ROW_NUMBER() OVER (
                            PARTITION BY owned.row_index
                            ORDER BY CASE owned.slot WHEN 'main' THEN 0 WHEN 'trade' THEN 1 ELSE 2 END, observation.transaction_id
                        ) AS rank
                    FROM (
                        SELECT r.row_index, o.observation_id, o.slot
                        FROM csv_import_record r
                        JOIN observation_record o ON o.record_id = r.record_id
                        WHERE r.csv_import_id = '$csvImportId'
                        UNION ALL
                        SELECT r.row_index, g.observation_id, g.slot
                        FROM csv_import_record r
                        JOIN import_group_member m ON m.record_id = r.record_id
                        JOIN observation_group g ON g.group_id = m.group_id
                        WHERE r.csv_import_id = '$csvImportId'
                    ) owned
                    JOIN observation ON observation.id = owned.observation_id
                )
                WHERE rank = 1
            ) source_rows ON source_rows.row_index = t.row_index
            ORDER BY t.row_index
            LIMIT $limit OFFSET $offset
            """.trimIndent()

        return database
            .query(sql) { cursor ->
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
        database.execute("DROP TABLE IF EXISTS $tableName")
    }

    /**
     * Updates the import status of a specific row. Which transfer the row booked is not kept here: its observations say.
     *
     * @param tableName The name of the table
     * @param rowIndex The row index to update
     * @param status The import status to set
     */
    suspend fun updateRowStatus(
        tableName: String,
        rowIndex: Long,
        status: String,
    ) {
        database.execute("UPDATE $tableName SET import_status = '$status' WHERE row_index = $rowIndex")
    }

    /** Returns every row of [tableName] to the never-imported state, so a strategy re-run picks them up again. */
    suspend fun resetAllRowStatuses(tableName: String) {
        database.execute("UPDATE $tableName SET import_status = NULL")
    }
}

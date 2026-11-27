package com.moneymanager.ui.screens.csv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.moneymanager.csv.CsvParseResult
import com.moneymanager.csvimporter.sqliteTablePage
import com.moneymanager.csvimporter.sqliteTables
import com.moneymanager.domain.model.SqliteRowRef
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.ui.components.csv.CsvPreviewTable
import kotlin.uuid.Uuid

private const val PAGE_SIZE = 200

@Composable
internal fun SqliteTables(
    bytes: ByteArray,
    sourceRows: List<SqliteRowRef>,
) {
    var tables by remember(bytes) { mutableStateOf(emptyList<String>()) }
    var ref by remember(sourceRows) { mutableStateOf(sourceRows.firstOrNull()) }
    var table by remember(sourceRows) { mutableStateOf(ref?.table) }
    var offset by remember(table, ref) { mutableStateOf(0) }
    var page by remember(table, ref, offset, bytes) { mutableStateOf<CsvParseResult?>(null) }
    var error by remember(table, ref, offset, bytes) { mutableStateOf<String?>(null) }
    LaunchedEffect(bytes, table, ref, offset) {
        try {
            val selected = table
            if (selected == null) {
                tables = sqliteTables(bytes)
            } else {
                page = sqliteTablePage(bytes, selected, offset, PAGE_SIZE + 1, ref)
            }
        } catch (failure: IllegalArgumentException) {
            error = failure.message ?: "Could not read SQLite table"
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (sourceRows.isNotEmpty()) {
            sourceRows.forEach { row ->
                TextButton(onClick = {
                    ref = row
                    table = row.table
                }) { Text(row.label) }
            }
        }
        if (table == null) {
            Text("Tables in main")
            LazyColumn {
                items(tables.size, key = { tables[it] }) { index ->
                    val name = tables[index]
                    TextButton(onClick = { table = name }) { Text(name) }
                }
            }
        } else {
            Row {
                TextButton(onClick = {
                    ref = null
                    table = null
                }) { Text("All tables") }
                TextButton(onClick = { ref = null }, enabled = ref != null) { Text("main.$table") }
                TextButton(enabled = offset > 0, onClick = { offset -= PAGE_SIZE }) { Text("Previous") }
                TextButton(enabled = (page?.rows?.size ?: 0) > PAGE_SIZE, onClick = { offset += PAGE_SIZE }) { Text("Next") }
            }
            page?.let { data ->
                if (data.rows.isEmpty()) {
                    Text(if (ref == null) "No rows" else "Source row is absent from this snapshot")
                } else {
                    key(table, ref, offset) {
                        val columns =
                            remember(data) {
                                data.headers.mapIndexed {
                                    i,
                                    name,
                                    ->
                                    CsvColumn(CsvColumnId(Uuid.random()), i, name)
                                }
                            }
                        val rows = remember(data) { data.rows.take(PAGE_SIZE).mapIndexed { i, values -> CsvRow(offset + i + 1L, values) } }
                        CsvPreviewTable(columns, rows, modifier = Modifier.fillMaxSize(), showImportColumns = false)
                    }
                }
            }
            if (page == null && error == null) CircularProgressIndicator()
        }
        error?.let { Text(it) }
    }
}

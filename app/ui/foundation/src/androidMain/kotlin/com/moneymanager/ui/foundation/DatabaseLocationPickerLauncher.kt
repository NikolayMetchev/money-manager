package com.moneymanager.ui.foundation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.DEFAULT_DATABASE_NAME
import com.moneymanager.domain.model.DbLocation

private const val DATABASE_EXTENSION = ".db"

actual class DatabaseLocationPickerLauncher(
    private val setPendingMode: (DatabasePickerMode?) -> Unit,
) {
    actual fun launch(mode: DatabasePickerMode) {
        setPendingMode(mode)
    }
}

@Composable
actual fun rememberDatabaseLocationPicker(onResult: (DbLocation?) -> Unit): DatabaseLocationPickerLauncher {
    val context = LocalContext.current
    var pendingMode by remember { mutableStateOf<DatabasePickerMode?>(null) }
    val launcher = remember { DatabaseLocationPickerLauncher(setPendingMode = { pendingMode = it }) }

    when (pendingMode) {
        DatabasePickerMode.OPEN -> {
            val databaseNames =
                remember {
                    context
                        .databaseList()
                        .filter { it.endsWith(DATABASE_EXTENSION) }
                        .sorted()
                }
            OpenDatabaseDialog(
                databaseNames = databaseNames,
                onSelect = { name ->
                    pendingMode = null
                    onResult(DbLocation(name))
                },
                onDismiss = {
                    pendingMode = null
                    onResult(null)
                },
            )
        }
        DatabasePickerMode.CREATE -> {
            CreateDatabaseDialog(
                onConfirm = { name ->
                    pendingMode = null
                    onResult(DbLocation(normalizeName(name)))
                },
                onDismiss = {
                    pendingMode = null
                    onResult(null)
                },
            )
        }
        DatabasePickerMode.OPEN_OR_CREATE -> {
            val databaseNames =
                remember {
                    context
                        .databaseList()
                        .filter { it.endsWith(DATABASE_EXTENSION) }
                        .sorted()
                }
            OpenOrCreateDatabaseDialog(
                databaseNames = databaseNames,
                onSelect = { name ->
                    pendingMode = null
                    onResult(DbLocation(name))
                },
                onCreate = { name ->
                    pendingMode = null
                    onResult(DbLocation(normalizeName(name)))
                },
                onDismiss = {
                    pendingMode = null
                    onResult(null)
                },
            )
        }
        null -> Unit
    }

    return launcher
}

private fun normalizeName(name: String): String {
    val trimmed = name.trim()
    return if (trimmed.endsWith(DATABASE_EXTENSION)) trimmed else "$trimmed$DATABASE_EXTENSION"
}

@Composable
private fun OpenDatabaseDialog(
    databaseNames: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open database") },
        text = {
            if (databaseNames.isEmpty()) {
                Text("No databases found.")
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    databaseNames.forEach { name ->
                        TextButton(
                            onClick = { onSelect(name) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(name)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun OpenOrCreateDatabaseDialog(
    databaseNames: List<String>,
    onSelect: (String) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(DEFAULT_DATABASE_NAME) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open or create database") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (databaseNames.isNotEmpty()) {
                    Text("Open existing:")
                    databaseNames.forEach { existing ->
                        TextButton(
                            onClick = { onSelect(existing) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(existing)
                        }
                    }
                }
                Text("Or create new:")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Database name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun CreateDatabaseDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(DEFAULT_DATABASE_NAME) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create database") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Database name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

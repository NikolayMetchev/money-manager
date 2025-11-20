package com.moneymanager

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Dialog shown on startup when the default database file doesn't exist
 */
@Composable
fun DatabaseSelectionDialog(
    defaultPath: Path,
    onDatabaseSelected: (Path) -> Unit,
    onCancel: () -> Unit
) {
    var selectedPath by remember { mutableStateOf(defaultPath) }

    Dialog(onDismissRequest = onCancel) {
        Surface(
            modifier = Modifier
                .width(500.dp)
                .wrapContentHeight(),
            shape = MaterialTheme.shapes.medium,
            elevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Database Setup",
                    style = MaterialTheme.typography.h5
                )

                Text(
                    text = "No database file found. Would you like to create a new database?",
                    style = MaterialTheme.typography.body1
                )

                Divider()

                // Display selected path
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Database location:",
                        style = MaterialTheme.typography.subtitle2
                    )
                    Text(
                        text = selectedPath.toString(),
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )
                }

                // Choose different location button
                OutlinedButton(
                    onClick = {
                        val fileDialog = FileDialog(null as Frame?, "Choose Database Location", FileDialog.SAVE)
                        fileDialog.file = "default.db"
                        fileDialog.isVisible = true

                        val selectedFile = fileDialog.file
                        val selectedDir = fileDialog.directory

                        if (selectedFile != null && selectedDir != null) {
                            selectedPath = Paths.get(selectedDir, selectedFile)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Choose Different Location...")
                }

                Divider()

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(onClick = onCancel) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = { onDatabaseSelected(selectedPath) }
                    ) {
                        Text("Create Database")
                    }
                }
            }
        }
    }
}

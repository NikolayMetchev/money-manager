package com.moneymanager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
@Suppress("FunctionNaming", "LongMethod")
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
            DatabaseSelectionContent(
                selectedPath = selectedPath,
                onPathChange = { selectedPath = it },
                onDatabaseSelected = { onDatabaseSelected(selectedPath) },
                onCancel = onCancel
            )
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun DatabaseSelectionContent(
    selectedPath: Path,
    onPathChange: (Path) -> Unit,
    onDatabaseSelected: () -> Unit,
    onCancel: () -> Unit
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

        DatabasePathDisplay(selectedPath)

        DatabaseLocationButton(onPathChange = onPathChange)

        Divider()

        DatabaseActionButtons(
            onCancel = onCancel,
            onConfirm = onDatabaseSelected
        )
    }
}

@Suppress("FunctionNaming")
@Composable
private fun DatabasePathDisplay(selectedPath: Path) {
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
}

@Suppress("FunctionNaming")
@Composable
private fun DatabaseLocationButton(onPathChange: (Path) -> Unit) {
    OutlinedButton(
        onClick = {
            val newPath = showFileChooserDialog()
            newPath?.let { onPathChange(it) }
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Choose Different Location...")
    }
}

@Suppress("FunctionNaming")
@Composable
private fun DatabaseActionButtons(
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
    ) {
        TextButton(onClick = onCancel) {
            Text("Cancel")
        }
        Button(onClick = onConfirm) {
            Text("Create Database")
        }
    }
}

private fun showFileChooserDialog(): Path? {
    val fileDialog = FileDialog(null as Frame?, "Choose Database Location", FileDialog.SAVE)
    fileDialog.file = "default.db"
    fileDialog.isVisible = true

    val selectedFile = fileDialog.file
    val selectedDir = fileDialog.directory

    return if (selectedFile != null && selectedDir != null) {
        Paths.get(selectedDir, selectedFile)
    } else {
        null
    }
}

package com.moneymanager.ui

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
import com.moneymanager.database.DbLocation
import java.nio.file.Path

/**
 * Dialog shown on startup when the default database file doesn't exist
 */
@Composable
fun DatabaseSelectionDialog(
    defaultPath: DbLocation,
    onDatabaseSelected: (Path) -> Unit,
    onCancel: () -> Unit,
    onShowFileChooser: () -> Path?,
) {
    var selectedPath by remember { mutableStateOf(defaultPath.path) }

    Dialog(onDismissRequest = onCancel) {
        Surface(
            modifier =
                Modifier
                    .width(500.dp)
                    .wrapContentHeight(),
            shape = MaterialTheme.shapes.medium,
            elevation = 8.dp,
        ) {
            DatabaseSelectionContent(
                selectedPath = selectedPath,
                onPathChange = { selectedPath = it },
                onDatabaseSelected = { onDatabaseSelected(selectedPath) },
                onCancel = onCancel,
                onShowFileChooser = onShowFileChooser,
            )
        }
    }
}

@Composable
private fun DatabaseSelectionContent(
    selectedPath: Path,
    onPathChange: (Path) -> Unit,
    onDatabaseSelected: () -> Unit,
    onCancel: () -> Unit,
    onShowFileChooser: () -> Path?,
) {
    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Database Setup",
            style = MaterialTheme.typography.h5,
        )

        Text(
            text = "No database file found. Would you like to create a new database?",
            style = MaterialTheme.typography.body1,
        )

        Divider()

        DatabasePathDisplay(selectedPath)

        DatabaseLocationButton(
            onPathChange = onPathChange,
            onShowFileChooser = onShowFileChooser,
        )

        Divider()

        DatabaseActionButtons(
            onCancel = onCancel,
            onConfirm = onDatabaseSelected,
        )
    }
}

@Composable
private fun DatabasePathDisplay(selectedPath: Path) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Database location:",
            style = MaterialTheme.typography.subtitle2,
        )
        Text(
            text = selectedPath.toString(),
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun DatabaseLocationButton(
    onPathChange: (Path) -> Unit,
    onShowFileChooser: () -> Path?,
) {
    OutlinedButton(
        onClick = {
            val newPath = onShowFileChooser()
            newPath?.let { onPathChange(it) }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Choose Different Location...")
    }
}

@Composable
private fun DatabaseActionButtons(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
        TextButton(onClick = onCancel) {
            Text("Cancel")
        }
        Button(onClick = onConfirm) {
            Text("Create Database")
        }
    }
}

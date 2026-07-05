@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.compose.filepicker.manualFolderEntrySupported
import com.moneymanager.compose.filepicker.rememberFolderPicker
import com.moneymanager.domain.model.importdirectory.ImportDirectoryProvider
import com.moneymanager.importfilesource.DriveFolderBrowser

/**
 * Dialog to add an import directory: name, provider (Local folder / Google Drive), and the folder
 * (picked for local; browsed from the user's Drive hierarchy via a popup for Google Drive). The
 * directory only downloads files into the Imports section — the user runs "Import All" there.
 */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun AddImportDirectoryDialog(
    driveFolderBrowser: DriveFolderBrowser?,
    onDismiss: () -> Unit,
    onCreate: (name: String, provider: ImportDirectoryProvider, folderRef: String, displayPath: String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf(ImportDirectoryProvider.LOCAL) }
    var folderRef by remember { mutableStateOf("") }
    var localFolderName by remember { mutableStateOf<String?>(null) }
    var driveFolderName by remember { mutableStateOf<String?>(null) }
    var showDrivePicker by remember { mutableStateOf(false) }

    val folderPicker =
        rememberFolderPicker { picked ->
            if (picked != null) {
                folderRef = picked.ref
                localFolderName = picked.displayName
                driveFolderName = null
                // Pre-fill the directory name with the folder name (only if the user hasn't typed one).
                if (name.isBlank()) name = picked.displayName
            }
        }

    val canCreate = name.isNotBlank() && folderRef.isNotBlank()

    // Show the Drive picker INSTEAD of this dialog (not nested): a second AlertDialog stacked on top of
    // this one does not render reliably. This composable stays in composition, so name/provider state is
    // preserved; we return here once a folder is picked or the picker is dismissed.
    if (showDrivePicker && driveFolderBrowser != null) {
        DriveFolderPickerDialog(
            browser = driveFolderBrowser,
            onDismiss = { showDrivePicker = false },
            onSelected = { id, leafName, fullPath ->
                folderRef = id
                driveFolderName = fullPath
                // Pre-fill the directory name with the leaf folder name (only if the user hasn't typed one).
                if (name.isBlank()) name = leafName
                showDrivePicker = false
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add import directory") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = provider == ImportDirectoryProvider.LOCAL,
                        onClick = {
                            provider = ImportDirectoryProvider.LOCAL
                            folderRef = ""
                            localFolderName = null
                            driveFolderName = null
                        },
                        label = { Text("Local folder") },
                    )
                    FilterChip(
                        selected = provider == ImportDirectoryProvider.GDRIVE,
                        onClick = {
                            provider = ImportDirectoryProvider.GDRIVE
                            folderRef = ""
                            localFolderName = null
                        },
                        enabled = driveFolderBrowser != null,
                        label = { Text("Google Drive") },
                    )
                }

                if (provider == ImportDirectoryProvider.LOCAL) {
                    if (manualFolderEntrySupported) {
                        OutlinedTextField(
                            value = folderRef,
                            onValueChange = {
                                folderRef = it
                                // A hand-typed path is its own display; drop any stale picked name.
                                localFolderName = null
                            },
                            label = { Text("Folder path") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(onClick = { folderPicker.launch() }) { Text("Browse…") }
                    } else {
                        // SAF-only platforms: the folder ref is an opaque content:// URI, so never show
                        // or edit it — mirror the Drive branch and show the picked folder's name instead.
                        OutlinedButton(onClick = { folderPicker.launch() }) {
                            Text(if (localFolderName == null) "Choose folder…" else "Change folder")
                        }
                        localFolderName?.let { Text("Selected: $it", style = MaterialTheme.typography.bodyMedium) }
                    }
                } else {
                    OutlinedButton(
                        enabled = driveFolderBrowser != null,
                        onClick = { showDrivePicker = true },
                    ) { Text(if (driveFolderName == null) "Browse Google Drive…" else "Change folder") }
                    driveFolderName?.let { Text("Selected: $it", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canCreate,
                onClick = {
                    // Drive: the full Drive path. Local: the picked folder's name when the ref is an
                    // opaque URI; null when the ref is a readable path (typed, or JVM picker where
                    // displayName == ref).
                    val displayPath =
                        when (provider) {
                            ImportDirectoryProvider.GDRIVE -> driveFolderName
                            ImportDirectoryProvider.LOCAL -> localFolderName?.takeIf { it != folderRef }
                        }
                    onCreate(name.trim(), provider, folderRef.trim(), displayPath)
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

package com.moneymanager.ui.screens

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.moneymanager.importfilesource.DriveFolderBrowser
import com.moneymanager.importfilesource.ImportFolder
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.launch

/**
 * A popup that navigates the user's Google Drive folder hierarchy one level at a time (like Drive
 * itself): tap a folder to open it, use the breadcrumb to go back, Enter or "Use this folder" to pick
 * the currently-open folder. [onSelected] receives the folder id, its leaf name, and its full path.
 */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun DriveFolderPickerDialog(
    browser: DriveFolderBrowser,
    onDismiss: () -> Unit,
    onSelected: (id: String, leafName: String, fullPath: String) -> Unit,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    // The two synthetic top-level roots, mirroring Drive's app: "My Drive" and "Shared with me".
    val roots =
        remember(browser) {
            listOf(
                ImportFolder(browser.rootFolderId, "My Drive"),
                ImportFolder(browser.sharedWithMeFolderId, "Shared with me"),
            )
        }
    // The descended folders (empty = the two-root home). The last entry is the currently-open folder.
    val path = remember { mutableStateListOf<ImportFolder>() }
    var children by remember { mutableStateOf<List<ImportFolder>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    val currentParentId = path.lastOrNull()?.id
    // At the home level, "Shared with me" isn't a real folder, so it can't be picked as a directory.
    val canUseCurrent = currentParentId != null && currentParentId != browser.sharedWithMeFolderId

    fun pathOf(extra: ImportFolder?): String = (path + listOfNotNull(extra)).joinToString(" / ") { it.name }

    fun useCurrent() {
        if (canUseCurrent) path.lastOrNull()?.let { onSelected(it.id, it.name, pathOf(null)) }
    }

    fun load() {
        // Home level: show the two roots without an API call.
        if (currentParentId == null) {
            children = roots
            error = null
            loading = false
            return
        }
        loading = true
        error = null
        children = null
        scope.launch {
            try {
                children = browser.listChildFolders(null, currentParentId)
            } catch (expected: Exception) {
                error = expected.message ?: "Failed to list Google Drive folders"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(currentParentId) { load() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a Google Drive folder") },
        text = {
            Column(
                modifier =
                    Modifier
                        .focusRequester(focusRequester)
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
                            if (event.type == KeyEventType.KeyDown && isEnter && canUseCurrent) {
                                useCurrent()
                                true
                            } else {
                                false
                            }
                        },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Breadcrumb: Drive / My Drive / subfolder …
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = { path.clear() }, enabled = path.isNotEmpty()) { Text("Drive") }
                    path.forEachIndexed { index, folder ->
                        Text("/", style = MaterialTheme.typography.bodyMedium)
                        TextButton(
                            onClick = { while (path.size > index + 1) path.removeAt(path.size - 1) },
                            enabled = index < path.lastIndex,
                        ) { Text(folder.name) }
                    }
                }
                HorizontalDivider()

                if (loading) CircularProgressIndicator()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

                children?.let { folders ->
                    if (!loading && folders.isEmpty()) {
                        Text("No subfolders here.", style = MaterialTheme.typography.bodySmall)
                    }
                    LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                        items(folders) { folder ->
                            // At the home level the entries are the two roots — open them, don't "Select".
                            val selectable = currentParentId != null && folder.id != browser.sharedWithMeFolderId
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                TextButton(onClick = { path.add(folder) }) { Text("📁 ${folder.name}") }
                                if (selectable) {
                                    TextButton(
                                        onClick = { onSelected(folder.id, folder.name, pathOf(folder)) },
                                    ) { Text("Select") }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canUseCurrent, onClick = { useCurrent() }) { Text("Use this folder") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

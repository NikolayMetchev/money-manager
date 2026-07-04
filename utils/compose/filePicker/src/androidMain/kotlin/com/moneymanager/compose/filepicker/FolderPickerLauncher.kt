package com.moneymanager.compose.filepicker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Android folder picking via the Storage Access Framework: OpenDocumentTree yields a content-tree
 * URI whose read permission is persisted so the import scanner can re-open the folder across app
 * restarts. The URI itself is unreadable to humans, so the picked folder's display name is resolved
 * up front and returned alongside it.
 */
@Composable
actual fun rememberFolderPicker(onResult: (PickedFolder?) -> Unit): FolderPickerLauncher {
    val context = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { uri: Uri? ->
            onResult(uri?.let { pickedFolderFromTreeUri(context, it) })
        }
    return remember(launcher) { FolderPickerLauncher { launcher.launch(null) } }
}

actual val manualFolderEntrySupported: Boolean = false

actual class FolderPickerLauncher(
    private val launcher: () -> Unit,
) {
    actual fun launch() {
        launcher()
    }
}

private fun pickedFolderFromTreeUri(
    context: Context,
    treeUri: Uri,
): PickedFolder {
    context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
    val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
    val displayName = queryDisplayName(context, documentUri) ?: displayNameFromDocumentId(treeDocId)
    return PickedFolder(ref = treeUri.toString(), displayName = displayName)
}

private fun queryDisplayName(
    context: Context,
    documentUri: Uri,
): String? =
    try {
        context.contentResolver
            .query(documentUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

/**
 * Fallback when the provider won't answer a display-name query: document ids like
 * "primary:Statements/2024" end with the folder's relative path, so the last path segment is the
 * closest thing to a name.
 */
internal fun displayNameFromDocumentId(documentId: String): String =
    documentId
        .substringAfterLast(':')
        .substringAfterLast('/')
        .ifBlank { documentId }

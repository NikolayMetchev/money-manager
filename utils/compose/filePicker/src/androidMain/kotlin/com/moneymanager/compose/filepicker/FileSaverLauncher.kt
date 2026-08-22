@file:Suppress("UnusedPrivateProperty") // These properties are used by rememberFileSaver

package com.moneymanager.compose.filepicker

import android.content.Context
import android.net.Uri

actual class FileSaverLauncher(
    private val mimeType: String,
    private val launcher: (String) -> Unit,
    private val context: Context,
    private val onResult: () -> Unit,
    private val setPendingContent: (String) -> Unit,
) {
    actual fun launch(
        fileName: String,
        content: String,
    ) {
        setPendingContent(content)
        launcher(fileName)
    }
}

/** Writes [content] to [uri]; a failed write is swallowed, as no caller acts on the outcome. */
internal fun writeContentToUri(
    context: Context,
    uri: Uri,
    content: String,
) {
    try {
        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            outputStream.write(content.toByteArray(Charsets.UTF_8))
        }
    } catch (_: Exception) {
        // Nothing to report back to.
    }
}

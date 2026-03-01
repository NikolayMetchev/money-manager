@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.compose.filepicker

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.time.Instant

@Composable
actual fun rememberFilePicker(
    mimeTypes: List<String>,
    onResult: (FilePickerResult?) -> Unit,
): FilePickerLauncher {
    val context = LocalContext.current

    val launcher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            if (uri != null) {
                val result = readFileContent(context, uri)
                onResult(result)
            } else {
                onResult(null)
            }
        }

    return remember(launcher, mimeTypes) {
        FilePickerLauncher(
            mimeTypes = mimeTypes.toTypedArray(),
            launcher = { types -> launcher.launch(types) },
        )
    }
}

private fun readFileContent(
    context: Context,
    uri: Uri,
): FilePickerResult? {
    return try {
        val fileName = getFileName(context, uri) ?: "unknown.csv"
        val lastModified = getLastModified(context, uri)
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val content = readStreamAsString(inputStream)
        FilePickerResult(fileName = fileName, content = content, lastModified = lastModified)
    } catch (_: Exception) {
        null
    }
}

private fun getFileName(
    context: Context,
    uri: Uri,
): String? {
    return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) {
                cursor.getString(nameIndex)
            } else {
                null
            }
        } else {
            null
        }
    }
}

private fun getLastModified(
    context: Context,
    uri: Uri,
): Instant? =
    try {
        queryLastModifiedMillis(context, uri)
            ?.takeIf { it > 0 }
            ?.let { Instant.fromEpochMilliseconds(it) }
    } catch (_: Exception) {
        null
    }

private fun queryLastModifiedMillis(
    context: Context,
    uri: Uri,
): Long? =
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        if (index >= 0) cursor.getLong(index) else null
    }

/**
 * Reads an InputStream as a UTF-8 string.
 * Extracted for testability.
 */
internal fun readStreamAsString(inputStream: InputStream): String =
    inputStream.use { stream ->
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            reader.readText()
        }
    }

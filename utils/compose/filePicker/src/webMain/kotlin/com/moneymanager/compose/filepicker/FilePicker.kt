package com.moneymanager.compose.filepicker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

@Composable
actual fun rememberFilePicker(
    mimeTypes: List<String>,
    onResult: (FilePickerResult?) -> Unit,
): FilePickerLauncher {
    val scope = rememberCoroutineScope()
    return remember(mimeTypes, onResult) {
        FilePickerLauncher(mimeTypes) { result -> scope.launch { onResult(result) } }
    }
}

@Composable
actual fun rememberMultipleBinaryFilePicker(
    mimeTypes: List<String>,
    onResult: (List<BinaryFilePickerResult>) -> Unit,
): MultipleBinaryFilePickerLauncher {
    val scope = rememberCoroutineScope()
    return remember(mimeTypes, onResult) {
        MultipleBinaryFilePickerLauncher(mimeTypes) { results -> scope.launch { onResult(results) } }
    }
}

actual class FilePickerLauncher(
    private val mimeTypes: List<String>,
    private val onResult: (FilePickerResult?) -> Unit,
) {
    actual fun launch() {
        pickBrowserFiles(mimeTypes, multiple = false, binary = false) { files ->
            onResult(files.firstOrNull()?.toTextResult())
        }
    }
}

actual class MultipleBinaryFilePickerLauncher(
    private val mimeTypes: List<String>,
    private val onResult: (List<BinaryFilePickerResult>) -> Unit,
) {
    actual fun launch() {
        pickBrowserFiles(mimeTypes, multiple = true, binary = true) { files ->
            onResult(files.mapNotNull { file -> file.bytes?.let { BinaryFilePickerResult(file.name, it, file.lastModified) } })
        }
    }
}

private fun PickedBrowserFile.toTextResult(): FilePickerResult? = text?.let { FilePickerResult(name, it, lastModified) }

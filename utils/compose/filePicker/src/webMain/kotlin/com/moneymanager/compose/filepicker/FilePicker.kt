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
actual fun rememberMultipleFilePicker(
    mimeTypes: List<String>,
    onResult: (List<FilePickerResult>) -> Unit,
): MultipleFilePickerLauncher {
    val scope = rememberCoroutineScope()
    return remember(mimeTypes, onResult) {
        MultipleFilePickerLauncher(mimeTypes) { results -> scope.launch { onResult(results) } }
    }
}

@Composable
actual fun rememberBinaryFilePicker(
    mimeTypes: List<String>,
    onResult: (BinaryFilePickerResult?) -> Unit,
): BinaryFilePickerLauncher {
    val scope = rememberCoroutineScope()
    return remember(mimeTypes, onResult) {
        BinaryFilePickerLauncher(mimeTypes) { result -> scope.launch { onResult(result) } }
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

actual class MultipleFilePickerLauncher(
    private val mimeTypes: List<String>,
    private val onResult: (List<FilePickerResult>) -> Unit,
) {
    actual fun launch() {
        pickBrowserFiles(mimeTypes, multiple = true, binary = false) { files ->
            onResult(files.mapNotNull { it.toTextResult() })
        }
    }
}

actual class BinaryFilePickerLauncher(
    private val mimeTypes: List<String>,
    private val onResult: (BinaryFilePickerResult?) -> Unit,
) {
    actual fun launch() {
        pickBrowserFiles(mimeTypes, multiple = false, binary = true) { files ->
            onResult(
                files.firstOrNull()?.let { file ->
                    file.bytes?.let { BinaryFilePickerResult(file.name, it, file.lastModified) }
                },
            )
        }
    }
}

private fun PickedBrowserFile.toTextResult(): FilePickerResult? = text?.let { FilePickerResult(name, it, lastModified) }

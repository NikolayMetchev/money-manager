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
        FilePickerLauncher(
            mimeTypes = mimeTypes,
            onResult = { result ->
                scope.launch {
                    onResult(result)
                }
            },
        )
    }
}

@Composable
actual fun rememberMultipleFilePicker(
    mimeTypes: List<String>,
    onResult: (List<FilePickerResult>) -> Unit,
): MultipleFilePickerLauncher {
    val scope = rememberCoroutineScope()

    return remember(mimeTypes, onResult) {
        MultipleFilePickerLauncher(
            mimeTypes = mimeTypes,
            onResult = { results ->
                scope.launch {
                    onResult(results)
                }
            },
        )
    }
}

@Composable
actual fun rememberBinaryFilePicker(
    mimeTypes: List<String>,
    onResult: (BinaryFilePickerResult?) -> Unit,
): BinaryFilePickerLauncher {
    val scope = rememberCoroutineScope()

    return remember(mimeTypes, onResult) {
        BinaryFilePickerLauncher(
            mimeTypes = mimeTypes,
            onResult = { result ->
                scope.launch {
                    onResult(result)
                }
            },
        )
    }
}

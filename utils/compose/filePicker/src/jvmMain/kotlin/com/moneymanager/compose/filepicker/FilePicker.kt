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

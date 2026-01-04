package com.moneymanager.compose.filepicker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

@Composable
actual fun rememberFileSaver(
    mimeType: String,
    onResult: (FileSaverResult?) -> Unit,
): FileSaverLauncher {
    val scope = rememberCoroutineScope()

    return remember(mimeType, onResult) {
        FileSaverLauncher(
            mimeType = mimeType,
            onResult = { result ->
                scope.launch {
                    onResult(result)
                }
            },
        )
    }
}

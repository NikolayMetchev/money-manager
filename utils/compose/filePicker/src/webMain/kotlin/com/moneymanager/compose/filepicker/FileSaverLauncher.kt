package com.moneymanager.compose.filepicker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

@Composable
actual fun rememberFileSaver(
    mimeType: String,
    onResult: () -> Unit,
): FileSaverLauncher {
    val scope = rememberCoroutineScope()
    return remember(mimeType, onResult) {
        FileSaverLauncher(mimeType) { scope.launch { onResult() } }
    }
}

// A browser saves through a download: where it lands is the browser's choice (or the person's, if it asks).
actual class FileSaverLauncher(
    private val mimeType: String,
    private val onResult: () -> Unit,
) {
    actual fun launch(
        fileName: String,
        content: String,
    ) {
        downloadText(fileName, content, mimeType)
        onResult()
    }
}

package com.moneymanager.compose.filepicker

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberFileSaver(
    mimeType: String,
    onResult: (FileSaverResult?) -> Unit,
): FileSaverLauncher {
    val context = LocalContext.current
    var pendingContent by remember { mutableStateOf<String?>(null) }

    val launcher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument(mimeType),
        ) { uri: Uri? ->
            if (uri != null && pendingContent != null) {
                val result = writeContentToUri(context, uri, pendingContent!!)
                onResult(result)
            } else {
                onResult(null)
            }
            pendingContent = null
        }

    return remember(launcher, mimeType) {
        FileSaverLauncher(
            mimeType = mimeType,
            launcher = { fileName -> launcher.launch(fileName) },
            context = context,
            onResult = onResult,
            setPendingContent = { content -> pendingContent = content },
        )
    }
}

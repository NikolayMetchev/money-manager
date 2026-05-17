package com.moneymanager.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

internal class DialogSaveState {
    var errorMessage by mutableStateOf<String?>(null)
    var isSaving by mutableStateOf(false)
}

@Composable
internal fun rememberDialogSaveState(): DialogSaveState = remember { DialogSaveState() }

@Composable
internal fun ErrorMessageText(error: String) {
    Text(
        text = error,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
}

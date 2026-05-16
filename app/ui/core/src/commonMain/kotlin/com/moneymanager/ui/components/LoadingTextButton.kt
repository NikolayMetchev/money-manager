package com.moneymanager.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LoadingTextButton(
    onClick: () -> Unit,
    enabled: Boolean,
    loading: Boolean,
    label: String,
    loadingIndicatorModifier: Modifier = Modifier,
    showLabelWhenLoading: Boolean = false,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = loadingIndicatorModifier.size(16.dp),
                strokeWidth = 2.dp,
            )
        }
        if (!loading || showLabelWhenLoading) {
            Text(label)
        }
    }
}

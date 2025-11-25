package com.moneymanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun SimpleFallbackErrorScreen(message: String, stackTrace: String) {
    // Ultra-simple error screen that cannot fail
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFFEBEE))
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Application Error",
            style = MaterialTheme.typography.headlineMedium,
            color = Color(0xFFB71C1C)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFF000000)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Stack Trace:",
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFF000000)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stackTrace,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFF424242)
        )
    }
}

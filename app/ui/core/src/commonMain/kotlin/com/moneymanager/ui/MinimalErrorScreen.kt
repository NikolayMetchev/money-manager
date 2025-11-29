package com.moneymanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Color constants for error screen
@Suppress("MagicNumber")
private val ERROR_BACKGROUND_COLOR = Color(0xFFFFEBEE)

@Suppress("MagicNumber")
private val ERROR_TITLE_COLOR = Color(0xFFB71C1C)

@Suppress("MagicNumber")
private val ERROR_TEXT_COLOR = Color(0xFF424242)

/**
 * Ultra-minimal error screen using only foundation and compose.ui.
 * This avoids Material3 dependencies to ensure it can display even if Material3 fails to initialize.
 */
@Composable
fun MinimalErrorScreen(
    message: String,
    stackTrace: String,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(ERROR_BACKGROUND_COLOR)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
    ) {
        androidx.compose.foundation.text.BasicText(
            text = "APPLICATION ERROR",
            style =
                androidx.compose.ui.text.TextStyle(
                    fontSize = 24.sp,
                    color = ERROR_TITLE_COLOR,
                    fontFamily = FontFamily.Default,
                ),
        )
        Spacer(modifier = Modifier.height(16.dp))
        androidx.compose.foundation.text.BasicText(
            text = message,
            style =
                androidx.compose.ui.text.TextStyle(
                    fontSize = 16.sp,
                    color = Color.Black,
                ),
        )
        Spacer(modifier = Modifier.height(24.dp))
        androidx.compose.foundation.text.BasicText(
            text = "Full Stack Trace:",
            style =
                androidx.compose.ui.text.TextStyle(
                    fontSize = 14.sp,
                    color = Color.Black,
                ),
        )
        Spacer(modifier = Modifier.height(8.dp))
        androidx.compose.foundation.text.BasicText(
            text = stackTrace,
            style =
                androidx.compose.ui.text.TextStyle(
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = ERROR_TEXT_COLOR,
                ),
        )
    }
}

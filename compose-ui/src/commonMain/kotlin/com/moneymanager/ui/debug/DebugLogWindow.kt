package com.moneymanager.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun DebugLogScreen() {
    val logs by LogCollector.logs.collectAsState()
    val listState = rememberLazyListState()
    var autoScroll by remember { mutableStateOf(true) }

    // Auto-scroll to bottom when new logs arrive
    LaunchedEffect(logs.size) {
        if (autoScroll && logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Control bar
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { LogCollector.clear() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Clear Logs")
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = autoScroll,
                    onCheckedChange = { autoScroll = it },
                )
                Text("Auto-scroll", style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.weight(1f))

            Text(
                text = "${logs.size} log entries",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()

        // Log entries
        if (logs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No logs yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(logs) { entry ->
                    LogEntryRow(entry)
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: LogEntry) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS") }
    val backgroundColor =
        when (entry.level) {
            LogLevel.ERROR -> MaterialTheme.colorScheme.errorContainer
            LogLevel.WARN -> Color(0xFFFFF9C4)
            LogLevel.INFO -> MaterialTheme.colorScheme.primaryContainer
            LogLevel.DEBUG -> MaterialTheme.colorScheme.surfaceContainer
        }

    val textColor =
        when (entry.level) {
            LogLevel.ERROR -> MaterialTheme.colorScheme.onErrorContainer
            LogLevel.WARN -> Color(0xFF827717)
            LogLevel.INFO -> MaterialTheme.colorScheme.onPrimaryContainer
            LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(backgroundColor, MaterialTheme.shapes.small)
                .padding(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Timestamp
            Text(
                text = timeFormat.format(Date(entry.timestamp)),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = textColor.copy(alpha = 0.7f),
            )

            // Level
            Text(
                text = entry.level.displayName,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = textColor,
                modifier = Modifier.width(50.dp),
            )

            // Message
            Text(
                text = entry.message,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = textColor,
                modifier = Modifier.weight(1f),
            )
        }

        // Show throwable if present
        entry.throwable?.let { throwable ->
            Text(
                text = "Exception: ${throwable::class.simpleName}: ${throwable.message}\n${
                    throwable.stackTraceToString().take(500)
                }",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = textColor.copy(alpha = 0.8f),
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            )
        }
    }
}

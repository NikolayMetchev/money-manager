package com.moneymanager.ui.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Simple in-memory log collector for debugging
 */
object LogCollector {
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private const val MAX_LOGS = 500

    fun log(level: LogLevel, message: String, throwable: Throwable? = null) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            message = message,
            throwable = throwable
        )
        _logs.value = (_logs.value + entry).takeLast(MAX_LOGS)
    }

    fun clear() {
        _logs.value = emptyList()
    }
}

data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val message: String,
    val throwable: Throwable? = null
)

enum class LogLevel(val displayName: String) {
    DEBUG("DEBUG"),
    INFO("INFO"),
    WARN("WARN"),
    ERROR("ERROR")
}

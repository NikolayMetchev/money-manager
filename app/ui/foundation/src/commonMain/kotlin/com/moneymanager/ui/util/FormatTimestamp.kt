@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.util

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

private val DISPLAY_FORMAT =
    LocalDateTime.Format {
        date(LocalDate.Formats.ISO)
        char(' ')
        hour()
        char(':')
        minute()
    }

fun Instant.displayDateTime(timeZone: TimeZone = TimeZone.currentSystemDefault()) = toLocalDateTime(timeZone).format(DISPLAY_FORMAT)

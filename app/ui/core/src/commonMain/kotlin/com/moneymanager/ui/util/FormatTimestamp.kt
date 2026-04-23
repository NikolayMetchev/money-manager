@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.util

import kotlinx.datetime.LocalDateTime.Formats.ISO
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

fun Instant.ISO(timeZone: TimeZone = TimeZone.currentSystemDefault()) =
    toLocalDateTime(timeZone)
        .format(ISO)

package com.moneymanager.tasks

import kotlin.time.Duration

/** Formats [elapsed] as `mm:ss`, or `h:mm:ss` once it passes an hour. */
fun formatElapsedTime(elapsed: Duration): String =
    maxOf(elapsed, Duration.ZERO).toComponents { hours, minutes, seconds, _ ->
        if (hours > 0) {
            "$hours:${minutes.formatTwoDigits()}:${seconds.formatTwoDigits()}"
        } else {
            "${minutes.formatTwoDigits()}:${seconds.formatTwoDigits()}"
        }
    }

private fun Number.formatTwoDigits(): String = toString().padStart(2, '0')

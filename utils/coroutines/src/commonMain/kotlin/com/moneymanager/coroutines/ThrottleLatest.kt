package com.moneymanager.coroutines

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transform
import kotlin.time.Duration

/**
 * Emits the first value at once, then at most one value per [period]: the latest one, so the last value is never
 * lost. Values that arrive while a period runs replace each other.
 *
 * For a query's change notifications ahead of re-running it: a bulk import commits hundreds of times, and a screen
 * listing imports needs one fresh result per moment, not one per commit.
 */
fun <T> Flow<T>.throttleLatest(period: Duration): Flow<T> =
    conflate().transform {
        emit(it)
        delay(period)
    }

package com.moneymanager.coroutines

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for code that suspends: a cancelled coroutine must stay cancelled, so a [CancellationException] is
 * rethrown instead of being captured as a failed [Result] that the caller then shrugs off.
 */
@Suppress("TooGenericExceptionCaught") // runCatching's own contract: any failure becomes the Result.
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        Result.failure(failure)
    }

package com.moneymanager.coroutines

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RunCatchingCancellableTest {
    @Test
    fun aValueIsReturnedAsASuccess() {
        assertEquals(42, runCatchingCancellable { 42 }.getOrThrow())
    }

    @Test
    fun aFailureIsCapturedLikeRunCatching() {
        val failure = runCatchingCancellable { error("boom") }.exceptionOrNull()

        assertIs<IllegalStateException>(failure)
    }

    @Test
    fun aCancellationIsRethrownAndNeverCaptured() {
        assertFailsWith<CancellationException> { runCatchingCancellable { throw CancellationException("stop") } }
    }
}

package com.moneymanager.coroutines

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class ThrottleLatestTest {
    @Test
    fun emitsTheFirstValueAtOnceThenTheLatestOncePerPeriod() =
        runTest {
            // Values every 30 ms against a 100 ms period: 1 at once, 4 (the latest of 2-4) at 100 ms, 5 at 200 ms.
            val values =
                flow {
                    for (value in 1..5) {
                        emit(value)
                        delay(30.milliseconds)
                    }
                }.throttleLatest(100.milliseconds).toList()

            assertEquals(listOf(1, 4, 5), values)
        }

    @Test
    fun passesEveryValueThroughWhenTheyArriveSlowerThanThePeriod() =
        runTest {
            val values =
                flow {
                    for (value in 1..3) {
                        emit(value)
                        delay(200.milliseconds)
                    }
                }.throttleLatest(100.milliseconds).toList()

            assertEquals(listOf(1, 2, 3), values)
        }
}

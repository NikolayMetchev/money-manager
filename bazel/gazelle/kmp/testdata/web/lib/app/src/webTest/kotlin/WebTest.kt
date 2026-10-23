package com.example.app

import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class WebTest {
    @Test
    fun engine() = runTest { engine() }
}

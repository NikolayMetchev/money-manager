package com.moneymanager.bazel.deps

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LibrariesTest {
    @Test
    fun everyLibraryLinksAndRuns() =
        runTest {
            assertEquals(
                listOf("coroutines", "2026-10-17", "io", "kotlin.String", "24691357802469135780", "sqldelight"),
                describeLibraries(),
            )
        }
}

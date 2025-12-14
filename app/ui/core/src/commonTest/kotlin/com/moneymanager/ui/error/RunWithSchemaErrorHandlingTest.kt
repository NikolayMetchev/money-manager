package com.moneymanager.ui.error

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class RunWithSchemaErrorHandlingTest {
    @AfterTest
    fun cleanup() {
        GlobalSchemaErrorState.clearError()
    }

    @Test
    fun runWithSchemaErrorHandling_returnsResult_whenBlockSucceeds() =
        runTest {
            val result =
                runWithSchemaErrorHandling {
                    "success"
                }

            assertEquals("success", result)
            assertNull(GlobalSchemaErrorState.schemaError.value)
        }

    @Test
    fun runWithSchemaErrorHandling_returnsNullAndReportsError_whenNoSuchColumnError() =
        runTest {
            val result =
                runWithSchemaErrorHandling {
                    throw Exception("no such column: row_index")
                }

            assertNull(result)
            assertNotNull(GlobalSchemaErrorState.schemaError.value)
            assertTrue(
                GlobalSchemaErrorState.schemaError.value!!.error.message!!.contains("no such column"),
            )
        }

    @Test
    fun runWithSchemaErrorHandling_returnsNullAndReportsError_whenNoSuchTableError() =
        runTest {
            val result =
                runWithSchemaErrorHandling {
                    throw Exception("no such table: CsvImportMetadata")
                }

            assertNull(result)
            assertNotNull(GlobalSchemaErrorState.schemaError.value)
            assertTrue(
                GlobalSchemaErrorState.schemaError.value!!.error.message!!.contains("no such table"),
            )
        }

    @Test
    fun runWithSchemaErrorHandling_rethrowsException_whenNotSchemaError() =
        runTest {
            try {
                runWithSchemaErrorHandling {
                    throw IllegalStateException("Some other error")
                }
                fail("Expected exception to be rethrown")
            } catch (e: IllegalStateException) {
                assertEquals("Some other error", e.message)
            }

            assertNull(GlobalSchemaErrorState.schemaError.value)
        }

    @Test
    fun runWithSchemaErrorHandling_usesProvidedDatabaseLocation() =
        runTest {
            runWithSchemaErrorHandling(databaseLocation = "/custom/path.db") {
                throw Exception("no such table: test")
            }

            assertEquals("/custom/path.db", GlobalSchemaErrorState.schemaError.value?.databaseLocation)
        }

    @Test
    fun runWithSchemaErrorHandling_usesDefaultDatabaseLocation_whenNotProvided() =
        runTest {
            runWithSchemaErrorHandling {
                throw Exception("no such table: test")
            }

            assertEquals("default", GlobalSchemaErrorState.schemaError.value?.databaseLocation)
        }
}

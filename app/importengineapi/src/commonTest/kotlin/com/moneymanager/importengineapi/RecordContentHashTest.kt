package com.moneymanager.importengineapi

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RecordContentHashTest {
    @Test
    fun the_same_values_and_strategy_hash_alike() =
        runTest {
            assertEquals(recordContentHash(listOf("a", "b"), "s"), recordContentHash(listOf("a", "b"), "s"))
            assertEquals(64, recordContentHash(listOf("a"), "s").length)
        }

    @Test
    fun a_changed_value_or_strategy_changes_the_hash() =
        runTest {
            val hash = recordContentHash(listOf("a", "b"), "s")
            assertNotEquals(hash, recordContentHash(listOf("a", "c"), "s"))
            assertNotEquals(hash, recordContentHash(listOf("a", "b"), "t"))
            assertNotEquals(hash, recordContentHash(listOf("b", "a"), "s"))
        }

    @Test
    fun values_cannot_run_into_each_other() =
        runTest {
            assertNotEquals(recordContentHash(listOf("ab", "c"), "s"), recordContentHash(listOf("a", "bc"), "s"))
            assertNotEquals(recordContentHash(listOf("", "a"), "s"), recordContentHash(listOf("a", ""), "s"))
            assertNotEquals(recordContentHash(listOf("a"), "s"), recordContentHash(listOf("a", ""), "s"))
            assertNotEquals(recordContentHash(listOf("1:a"), ""), recordContentHash(listOf("a"), "1:a"))
        }

    @Test
    fun the_strategy_config_hash_is_the_sha256_of_its_json() =
        runTest {
            assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", strategyConfigHash("hello world"))
        }
}

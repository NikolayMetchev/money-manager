package com.moneymanager.ui.screens.transactions

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.reconciliation.ShadowAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ManualEntrySuggestionCacheTest {
    private val suggestions =
        ManualEntrySuggestions(
            shadowAccounts =
                mapOf(AccountId(1) to ShadowAccount(AccountId(1), "Koinly · Revolut", "Koinly")),
        )

    @Test
    fun `suggestions are reused for the same pending entries until the database is written to`() {
        val cache = ManualEntrySuggestionCache()
        cache.put(setOf(1, 2), writeVersion = 5, suggestions)

        assertEquals(suggestions, cache.get(setOf(1, 2), writeVersion = 5))
        assertNull(cache.get(setOf(1, 2), writeVersion = 6))
        assertNull(cache.get(setOf(1), writeVersion = 5))
    }

    @Test
    fun `the last suggestions are shown while fresh ones load only when nothing was written since`() {
        val cache = ManualEntrySuggestionCache()
        assertNull(cache.peek(writeVersion = 5))
        cache.put(setOf(1), writeVersion = 5, suggestions)

        assertEquals(suggestions, cache.peek(writeVersion = 5))
        assertNull(cache.peek(writeVersion = 6))
    }
}

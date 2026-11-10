package com.moneymanager.localsettings

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UiStateStoreTest {
    @Serializable
    private data class Filter(
        val text: String,
        val ids: Set<Long>,
    )

    @Test
    fun `a stored value reads back`() {
        val store = UiStateStore(MemoryLocalSettings())
        val filter = Filter("abc", setOf(1, 2))
        store.put("f", Filter.serializer(), filter)
        assertEquals(filter, store.get("f", Filter.serializer()))
    }

    @Test
    fun `a missing value is null`() {
        assertNull(UiStateStore(MemoryLocalSettings()).get("x", String.serializer()))
    }

    @Test
    fun `a value that does not decode is null`() {
        val settings = MemoryLocalSettings()
        settings.putString("ui.f", "not json")
        assertNull(UiStateStore(settings).get("f", Filter.serializer()))
    }
}

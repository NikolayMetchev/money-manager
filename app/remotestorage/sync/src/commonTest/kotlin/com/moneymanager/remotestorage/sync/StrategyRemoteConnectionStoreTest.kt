package com.moneymanager.remotestorage.sync

import com.moneymanager.domain.StrategyKey
import com.moneymanager.domain.StrategyKind
import com.moneymanager.localsettings.LocalSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Records every value written so tests can assert what actually reaches the settings backend. */
private class RecordingLocalSettings : LocalSettings {
    private val delegate = InMemoryLocalSettings()
    val writtenValues = mutableListOf<String>()

    override fun getString(key: String): String? = delegate.getString(key)

    override fun putString(
        key: String,
        value: String,
    ) {
        writtenValues += value
        delegate.putString(key, value)
    }

    override fun remove(key: String) = delegate.remove(key)
}

class StrategyRemoteConnectionStoreTest {
    private val key = StrategyKey(StrategyKind.CSV, "Crypto.com")

    @Test
    fun `baseline round-trips and its persisted encoding is XML-safe`() {
        val settings = RecordingLocalSettings()
        val store = StrategyRemoteConnectionStore(settings)

        val baseline = StrategySyncedBaseline(remoteFileId = "1AbC_x-9", syncedRevision = "rev-42", syncedHash = "cbf29ce4")
        store.putBaseline(key, baseline)
        assertEquals(baseline, store.baseline(key))

        // Regression guard: on JVM these values land in java.util.prefs' prefs.xml, where a single
        // XML-illegal control character makes the WHOLE node fail to flush (every setting written that
        // session is silently lost). The encoding must therefore never contain control characters.
        assertTrue(settings.writtenValues.isNotEmpty())
        for (value in settings.writtenValues) {
            assertTrue(
                value.none { it.code < 0x20 && it != '\t' && it != '\n' && it != '\r' },
                "persisted value contains an XML-illegal control character: ${value.map { it.code }}",
            )
        }
    }

    @Test
    fun `null revision round-trips as null`() {
        val store = StrategyRemoteConnectionStore(InMemoryLocalSettings())
        store.putBaseline(key, StrategySyncedBaseline("file-1", syncedRevision = null, syncedHash = "abc"))
        assertNull(store.baseline(key)!!.syncedRevision)
    }

    @Test
    fun `a field that cannot round-trip is not persisted at all`() {
        val settings = RecordingLocalSettings()
        val store = StrategyRemoteConnectionStore(settings)
        // Separator collision and control characters must never poison the settings node.
        store.putBaseline(key, StrategySyncedBaseline("id|with|separator", "rev", "hash"))
        store.putBaseline(key, StrategySyncedBaseline("id", "rev", "hash" + Char(code = 1)))
        assertNull(store.baseline(key))
        assertTrue(settings.writtenValues.isEmpty())
    }

    @Test
    fun `a never-synced key has no baseline`() {
        val store = StrategyRemoteConnectionStore(InMemoryLocalSettings())
        store.putBaseline(key, StrategySyncedBaseline("file-1", "rev", "hash"))
        assertNull(store.baseline(StrategyKey(StrategyKind.API, "Unknown")))
    }
}

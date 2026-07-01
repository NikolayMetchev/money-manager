package com.moneymanager.remotestorage.sync

import com.moneymanager.domain.CsvResolution
import com.moneymanager.domain.CsvUnresolvedReference
import com.moneymanager.domain.LocalStrategyEntry
import com.moneymanager.domain.StrategyFileNaming
import com.moneymanager.domain.StrategyKey
import com.moneymanager.domain.StrategyKind
import com.moneymanager.domain.StrategyLibrary
import com.moneymanager.domain.StrategyParseResult
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.remotestorage.RemoteFile
import com.moneymanager.remotestorage.RemoteStorageException
import com.moneymanager.remotestorage.RemoteStorageProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A [StrategyLibrary] over an in-memory artifact set. The artifact JSON doubles as its own canonical
 * hash (so tests set content by string), and pulls are recorded and reflected back into [entries].
 */
private class FakeStrategyLibrary(
    initial: List<LocalStrategyEntry> = emptyList(),
) : StrategyLibrary {
    val entries = initial.associateBy { it.key }.toMutableMap()
    val applied = mutableListOf<Pair<StrategyKey, String>>()

    fun set(
        key: StrategyKey,
        json: String,
    ) {
        entries[key] = LocalStrategyEntry(key, json, json)
    }

    var lastListVersion: AppVersion? = null

    override suspend fun listLocal(appVersion: AppVersion): List<LocalStrategyEntry> {
        lastListVersion = appVersion
        return entries.values.toList()
    }

    override fun canonicalHash(
        key: StrategyKey,
        json: String,
    ): String = json

    override suspend fun parseIncoming(
        key: StrategyKey,
        json: String,
    ): StrategyParseResult = StrategyParseResult(key, emptyList())

    override suspend fun applyIncoming(
        key: StrategyKey,
        json: String,
        resolutions: Map<CsvUnresolvedReference, CsvResolution>,
    ) {
        applied += key to json
        // Simulate the pulled artifact now existing locally with the remote content.
        set(key, json)
    }
}

class StrategySyncControllerTest {
    private val version = AppVersion("test")

    private fun entry(
        name: String,
        json: String,
        kind: StrategyKind = StrategyKind.CSV,
    ): LocalStrategyEntry {
        val key = StrategyKey(kind, name)
        return LocalStrategyEntry(key, json, json)
    }

    private fun controllerWith(provider: InMemoryStorageProvider): StrategySyncController {
        val store = StrategyRemoteConnectionStore(InMemoryLocalSettings())
        return StrategySyncController(SingleProviderFactory(provider), store)
    }

    private suspend fun StrategySyncController.connectTo(provider: InMemoryStorageProvider) = connect(provider.id, null)

    private fun statusOf(
        controller: StrategySyncController,
        name: String,
        kind: StrategyKind = StrategyKind.CSV,
    ): StrategyItemStatus? {
        val target = StrategyKey(kind, name)
        val items = controller.state.value.items
        return items.firstOrNull { it.key == target }?.status
    }

    @Test
    fun `local-only strategy is NEW_LOCAL then uploaded and IN_SYNC`() =
        runTest {
            val provider = InMemoryStorageProvider()
            val library = FakeStrategyLibrary(listOf(entry("Wise", "wise-v1")))
            val controller = controllerWith(provider)
            controller.connectTo(provider)

            controller.refresh(library, version)
            assertEquals(StrategyItemStatus.NEW_LOCAL, statusOf(controller, "Wise"))

            val summary = controller.syncNow(library, version)
            assertEquals(1, summary.uploaded)
            assertEquals(StrategyItemStatus.IN_SYNC, statusOf(controller, "Wise"))
            assertEquals(version, library.lastListVersion)
            // Uploaded under the kind-suffixed filename.
            assertTrue(provider.list().any { it.name == StrategyFileNaming.fileName(StrategyKey(StrategyKind.CSV, "Wise")) })
        }

    @Test
    fun `remote-only strategy is AVAILABLE and only imported when selected`() =
        runTest {
            val provider = InMemoryStorageProvider()
            provider.upload(null, StrategyFileNaming.fileName(StrategyKey(StrategyKind.CSV, "Monzo")), "monzo-v1".encodeToByteArray())
            val library = FakeStrategyLibrary()
            val controller = controllerWith(provider)
            controller.connectTo(provider)

            controller.refresh(library, version)
            assertEquals(StrategyItemStatus.AVAILABLE, statusOf(controller, "Monzo"))

            // Sync without selecting it: nothing imported.
            controller.syncNow(library, version)
            assertTrue(library.applied.isEmpty())

            // Now select it: imported and IN_SYNC.
            val summary = controller.syncNow(library, version, selectedToPull = setOf(StrategyKey(StrategyKind.CSV, "Monzo")))
            assertEquals(1, summary.pulled)
            assertEquals("monzo-v1", library.applied.single().second)
            assertEquals(StrategyItemStatus.IN_SYNC, statusOf(controller, "Monzo"))
        }

    @Test
    fun `local edit after sync is LOCAL_AHEAD and re-uploads`() =
        runTest {
            val provider = InMemoryStorageProvider()
            val library = FakeStrategyLibrary(listOf(entry("Wise", "wise-v1")))
            val controller = controllerWith(provider)
            controller.connectTo(provider)
            controller.syncNow(library, version)

            library.set(StrategyKey(StrategyKind.CSV, "Wise"), "wise-v2")
            controller.refresh(library, version)
            assertEquals(StrategyItemStatus.LOCAL_AHEAD, statusOf(controller, "Wise"))

            controller.syncNow(library, version)
            assertEquals(StrategyItemStatus.IN_SYNC, statusOf(controller, "Wise"))
        }

    @Test
    fun `external remote change is REMOTE_AHEAD`() =
        runTest {
            val provider = InMemoryStorageProvider()
            val library = FakeStrategyLibrary(listOf(entry("Wise", "wise-v1")))
            val controller = controllerWith(provider)
            controller.connectTo(provider)
            controller.syncNow(library, version)

            val fileId = provider.list().single { it.name.startsWith("Wise") }.id
            provider.externalPush(fileId, "wise-remote".encodeToByteArray())
            controller.refresh(library, version)
            assertEquals(StrategyItemStatus.REMOTE_AHEAD, statusOf(controller, "Wise"))
        }

    @Test
    fun `both sides changed is CONFLICT and not auto-uploaded`() =
        runTest {
            val provider = InMemoryStorageProvider()
            val library = FakeStrategyLibrary(listOf(entry("Wise", "wise-v1")))
            val controller = controllerWith(provider)
            controller.connectTo(provider)
            controller.syncNow(library, version)

            val fileId = provider.list().single { it.name.startsWith("Wise") }.id
            provider.externalPush(fileId, "wise-remote".encodeToByteArray())
            library.set(StrategyKey(StrategyKind.CSV, "Wise"), "wise-local2")
            controller.refresh(library, version)
            assertEquals(StrategyItemStatus.CONFLICT, statusOf(controller, "Wise"))

            // A plain sync must not clobber the remote for a conflict.
            controller.syncNow(library, version)
            assertEquals("wise-remote", provider.download(fileId).decodeToString())

            // Forcing the upload makes local win.
            controller.syncNow(library, version, forceUpload = setOf(StrategyKey(StrategyKind.CSV, "Wise")))
            assertEquals("wise-local2", provider.download(fileId).decodeToString())
        }

    @Test
    fun `remote revision drift without content change stays LOCAL_AHEAD and auto-uploads`() =
        runTest {
            val provider = InMemoryStorageProvider()
            val library = FakeStrategyLibrary(listOf(entry("Crypto.com", "crypto-v1")))
            val controller = controllerWith(provider)
            controller.connectTo(provider)
            controller.syncNow(library, version)

            // The backend reports a new revision id for unchanged content (e.g. Drive's headRevisionId
            // drifting after an upload). Only the local copy was edited, so this must NOT be a conflict.
            val fileId = provider.list().single { it.name.startsWith("Crypto.com") }.id
            provider.externalPush(fileId, "crypto-v1".encodeToByteArray())
            library.set(StrategyKey(StrategyKind.CSV, "Crypto.com"), "crypto-v2")

            controller.refresh(library, version)
            assertEquals(StrategyItemStatus.LOCAL_AHEAD, statusOf(controller, "Crypto.com"))

            val summary = controller.syncNow(library, version)
            assertEquals(1, summary.uploaded)
            assertEquals("crypto-v2", provider.download(fileId).decodeToString())
            assertEquals(StrategyItemStatus.IN_SYNC, statusOf(controller, "Crypto.com"))
        }

    @Test
    fun `remote revision drift with no changes at all stays IN_SYNC`() =
        runTest {
            val provider = InMemoryStorageProvider()
            val library = FakeStrategyLibrary(listOf(entry("Wise", "wise-v1")))
            val controller = controllerWith(provider)
            controller.connectTo(provider)
            controller.syncNow(library, version)

            val fileId = provider.list().single { it.name.startsWith("Wise") }.id
            provider.externalPush(fileId, "wise-v1".encodeToByteArray())
            controller.refresh(library, version)
            assertEquals(StrategyItemStatus.IN_SYNC, statusOf(controller, "Wise"))
        }

    @Test
    fun `busy flag is cleared when a sync operation fails`() =
        runTest {
            val base = InMemoryStorageProvider()
            val provider =
                object : RemoteStorageProvider by base {
                    override suspend fun list(): List<RemoteFile> = throw RemoteStorageException("network down")
                }
            val library = FakeStrategyLibrary(listOf(entry("Wise", "wise-v1")))
            val store = StrategyRemoteConnectionStore(InMemoryLocalSettings())
            val controller = StrategySyncController(SingleProviderFactory(provider), store)
            controller.connect(provider.id, null)

            controller.beginBusy()
            assertTrue(controller.state.value.busy)
            assertFailsWith<RemoteStorageException> { controller.refresh(library, version) }
            assertFalse(controller.state.value.busy, "a failed refresh must not leave the UI stuck busy")

            controller.beginBusy()
            assertFailsWith<RemoteStorageException> { controller.syncNow(library, version) }
            assertFalse(controller.state.value.busy, "a failed sync must not leave the UI stuck busy")
        }

    @Test
    fun `identical content on both sides with no baseline is IN_SYNC not conflict`() =
        runTest {
            val provider = InMemoryStorageProvider()
            provider.upload(null, StrategyFileNaming.fileName(StrategyKey(StrategyKind.CSV, "Built-in")), "same".encodeToByteArray())
            val library = FakeStrategyLibrary(listOf(entry("Built-in", "same")))
            val controller = controllerWith(provider)
            controller.connectTo(provider)

            controller.refresh(library, version)
            assertEquals(StrategyItemStatus.IN_SYNC, statusOf(controller, "Built-in"))
        }
}

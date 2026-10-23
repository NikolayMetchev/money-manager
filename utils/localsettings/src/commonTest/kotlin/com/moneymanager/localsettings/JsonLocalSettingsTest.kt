package com.moneymanager.localsettings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonLocalSettingsTest {
    // One store, as the file the desktop app and the CLI share (or the localStorage item a browser's tabs share).
    private val store = MemorySettingsText()

    @Test
    fun missingTextReadsAsEmpty() {
        assertNull(JsonLocalSettings(store).getString(KEY_LAST_DATABASE))
    }

    @Test
    fun valuesPersistAcrossInstances() {
        JsonLocalSettings(store).putString(KEY_LAST_DATABASE, "/data/money.db")

        assertEquals("/data/money.db", JsonLocalSettings(store).getString(KEY_LAST_DATABASE))
    }

    @Test
    fun removeDeletesOnlyThatKey() {
        val settings = JsonLocalSettings(store)
        settings.putString(KEY_LAST_DATABASE, "/data/money.db")
        settings.putString(KEY_LAST_DIRECTORY, "/data")

        settings.remove(KEY_LAST_DATABASE)

        val reopened = JsonLocalSettings(store)
        assertNull(reopened.getString(KEY_LAST_DATABASE))
        assertEquals("/data", reopened.getString(KEY_LAST_DIRECTORY))
    }

    @Test
    fun anotherInstancesWritesAreSeenAndKept() {
        val desktop = JsonLocalSettings(store)
        val cli = JsonLocalSettings(store)
        desktop.putString(KEY_LAST_DIRECTORY, "/data")
        assertEquals("/data", cli.getString(KEY_LAST_DIRECTORY))

        cli.putString(KEY_LAST_DATABASE, "/data/cli.db")
        desktop.putString("other", "value")

        assertEquals("/data/cli.db", desktop.getString(KEY_LAST_DATABASE))
        val reopened = JsonLocalSettings(store)
        assertEquals("/data", reopened.getString(KEY_LAST_DIRECTORY))
        assertEquals("/data/cli.db", reopened.getString(KEY_LAST_DATABASE))
        assertEquals("value", reopened.getString("other"))
    }

    @Test
    fun controlCharactersRoundTrip() {
        val value = "token\u0001with\u0000controls\n\"quoted\""
        JsonLocalSettings(store).putString("secretish", value)

        assertEquals(value, JsonLocalSettings(store).getString("secretish"))
    }

    @Test
    fun corruptTextReadsAsEmptyAndTheNextWriteRecovers() {
        store.write("{not json")
        val settings = JsonLocalSettings(store)
        assertNull(settings.getString(KEY_LAST_DATABASE))

        settings.putString(KEY_LAST_DATABASE, "/data/money.db")

        assertEquals("/data/money.db", JsonLocalSettings(store).getString(KEY_LAST_DATABASE))
    }

    @Test
    fun nonStringValuesMakeTheTextReadAsEmpty() {
        store.write("""{"lastDirectory":"/data","lastDatabase":null}""")

        assertNull(JsonLocalSettings(store).getString(KEY_LAST_DIRECTORY))
    }

    @Test
    fun aSameSizeRewriteByAnotherInstanceIsSeen() {
        val desktop = JsonLocalSettings(store)
        val cli = JsonLocalSettings(store)
        desktop.putString(KEY_LAST_DATABASE, "/data/a.db")
        assertEquals("/data/a.db", cli.getString(KEY_LAST_DATABASE))

        desktop.putString(KEY_LAST_DATABASE, "/data/b.db")

        assertEquals("/data/b.db", cli.getString(KEY_LAST_DATABASE))
    }

    @Test
    fun putMissingKeepsExistingValues() {
        val settings = JsonLocalSettings(store)
        settings.putString(KEY_LAST_DIRECTORY, "/store")

        assertTrue(settings.putMissing(mapOf(KEY_LAST_DIRECTORY to "/other", KEY_LAST_DATABASE to "/data/money.db")))

        val reopened = JsonLocalSettings(store)
        assertEquals("/store", reopened.getString(KEY_LAST_DIRECTORY))
        assertEquals("/data/money.db", reopened.getString(KEY_LAST_DATABASE))
    }

    @Test
    fun keysAreWrittenSorted() {
        val settings = JsonLocalSettings(store)
        settings.putString("b", "2")
        settings.putString("a", "1")

        val text = checkNotNull(store.read())
        assertTrue(text.indexOf("\"a\"") < text.indexOf("\"b\""), text)
    }
}

private class MemorySettingsText : SettingsText {
    private var text: String? = null

    override fun read(): String? = text

    override fun write(text: String) {
        this.text = text
    }
}

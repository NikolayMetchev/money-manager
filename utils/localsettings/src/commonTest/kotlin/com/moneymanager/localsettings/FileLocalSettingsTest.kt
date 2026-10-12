package com.moneymanager.localsettings

import com.moneymanager.files.absolutePath
import com.moneymanager.files.readText
import com.moneymanager.files.writeText
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileLocalSettingsTest {
    // Native falls back to a relative temp directory when TMPDIR is unset.
    private val dir = Path(absolutePath(SystemTemporaryDirectory), "local-settings-test-${Random.nextLong().toULong()}")
    private val file = Path(dir, "nested", "settings.json")

    @AfterTest
    fun cleanUp() {
        val nested = Path(dir, "nested")
        if (SystemFileSystem.exists(nested)) SystemFileSystem.list(nested).forEach { SystemFileSystem.delete(it) }
        SystemFileSystem.delete(nested, mustExist = false)
        SystemFileSystem.delete(dir, mustExist = false)
    }

    @Test
    fun missingFileReadsAsEmpty() {
        assertNull(FileLocalSettings(file).getString(KEY_LAST_DATABASE))
    }

    @Test
    fun valuesPersistAcrossInstances() {
        FileLocalSettings(file).putString(KEY_LAST_DATABASE, "/data/money.db")

        assertEquals("/data/money.db", FileLocalSettings(file).getString(KEY_LAST_DATABASE))
    }

    @Test
    fun removeDeletesOnlyThatKey() {
        val settings = FileLocalSettings(file)
        settings.putString(KEY_LAST_DATABASE, "/data/money.db")
        settings.putString(KEY_LAST_DIRECTORY, "/data")

        settings.remove(KEY_LAST_DATABASE)

        val reopened = FileLocalSettings(file)
        assertNull(reopened.getString(KEY_LAST_DATABASE))
        assertEquals("/data", reopened.getString(KEY_LAST_DIRECTORY))
    }

    @Test
    fun anotherInstancesWritesAreSeenAndKept() {
        val desktop = FileLocalSettings(file)
        val cli = FileLocalSettings(file)
        desktop.putString(KEY_LAST_DIRECTORY, "/data")
        assertEquals("/data", cli.getString(KEY_LAST_DIRECTORY))

        cli.putString(KEY_LAST_DATABASE, "/data/cli.db")
        desktop.putString("other", "value")

        assertEquals("/data/cli.db", desktop.getString(KEY_LAST_DATABASE))
        val reopened = FileLocalSettings(file)
        assertEquals("/data", reopened.getString(KEY_LAST_DIRECTORY))
        assertEquals("/data/cli.db", reopened.getString(KEY_LAST_DATABASE))
        assertEquals("value", reopened.getString("other"))
    }

    @Test
    fun controlCharactersRoundTrip() {
        val value = "token\u0001with\u0000controls\n\"quoted\""
        FileLocalSettings(file).putString("secretish", value)

        assertEquals(value, FileLocalSettings(file).getString("secretish"))
    }

    @Test
    fun corruptFileReadsAsEmptyAndTheNextWriteRecovers() {
        writeText(file, "{not json")
        val settings = FileLocalSettings(file)
        assertNull(settings.getString(KEY_LAST_DATABASE))

        settings.putString(KEY_LAST_DATABASE, "/data/money.db")

        assertEquals("/data/money.db", FileLocalSettings(file).getString(KEY_LAST_DATABASE))
    }

    @Test
    fun nonStringValuesMakeTheFileReadAsEmpty() {
        writeText(file, """{"lastDirectory":"/data","lastDatabase":null}""")

        assertNull(FileLocalSettings(file).getString(KEY_LAST_DIRECTORY))
    }

    @Test
    fun aSameSizeRewriteByAnotherInstanceIsSeen() {
        val desktop = FileLocalSettings(file)
        val cli = FileLocalSettings(file)
        desktop.putString(KEY_LAST_DATABASE, "/data/a.db")
        assertEquals("/data/a.db", cli.getString(KEY_LAST_DATABASE))

        desktop.putString(KEY_LAST_DATABASE, "/data/b.db")

        assertEquals("/data/b.db", cli.getString(KEY_LAST_DATABASE))
    }

    @Test
    fun putMissingKeepsExistingValues() {
        val settings = FileLocalSettings(file)
        settings.putString(KEY_LAST_DIRECTORY, "/file")

        assertTrue(settings.putMissing(mapOf(KEY_LAST_DIRECTORY to "/other", KEY_LAST_DATABASE to "/data/money.db")))

        val reopened = FileLocalSettings(file)
        assertEquals("/file", reopened.getString(KEY_LAST_DIRECTORY))
        assertEquals("/data/money.db", reopened.getString(KEY_LAST_DATABASE))
    }

    @Test
    fun keysAreWrittenSorted() {
        val settings = FileLocalSettings(file)
        settings.putString("b", "2")
        settings.putString("a", "1")

        val text = readText(file)
        assertTrue(text.indexOf("\"a\"") < text.indexOf("\"b\""), text)
    }
}

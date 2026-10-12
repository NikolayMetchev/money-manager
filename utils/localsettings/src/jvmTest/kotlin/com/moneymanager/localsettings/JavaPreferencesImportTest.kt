package com.moneymanager.localsettings

import com.moneymanager.files.writeText
import java.nio.file.Files
import java.util.prefs.Preferences
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.io.files.Path as IoPath

class JavaPreferencesImportTest {
    private val root = Preferences.userRoot()
    private val nodePath = "/moneymanager-import-test-${Random.nextLong().toULong()}"
    private val dir = Files.createTempDirectory("prefs-import-test")
    private val file = IoPath(dir.toString(), "settings.json")

    @AfterTest
    fun cleanUp() {
        if (root.nodeExists(nodePath)) root.node(nodePath).removeNode()
        dir.toFile().deleteRecursively()
    }

    @Test
    fun importsMissingKeysThenDeletesTheNode() {
        root.node(nodePath).apply {
            put(KEY_LAST_DATABASE, "/prefs/money.db")
            put(KEY_LAST_DIRECTORY, "/prefs")
        }
        val target = FileLocalSettings(file).apply { putString(KEY_LAST_DIRECTORY, "/file") }

        importJavaPreferences(target, root, nodePath)

        val reopened = FileLocalSettings(file)
        assertEquals("/prefs/money.db", reopened.getString(KEY_LAST_DATABASE))
        assertEquals("/file", reopened.getString(KEY_LAST_DIRECTORY))
        assertFalse(root.nodeExists(nodePath))
    }

    @Test
    fun keepsTheNodeWhenTheFileCannotBeWritten() {
        root.node(nodePath).put(KEY_LAST_DATABASE, "/prefs/money.db")
        val blocker = IoPath(dir.toString(), "blocker")
        writeText(blocker, "a file where the settings directory should be")

        importJavaPreferences(FileLocalSettings(IoPath(blocker, "settings.json")), root, nodePath)

        assertTrue(root.nodeExists(nodePath))
        assertEquals("/prefs/money.db", root.node(nodePath).get(KEY_LAST_DATABASE, null))
    }

    @Test
    fun aMissingNodeIsANoOp() {
        importJavaPreferences(FileLocalSettings(file), root, nodePath)

        assertNull(FileLocalSettings(file).getString(KEY_LAST_DATABASE))
        assertFalse(root.nodeExists(nodePath))
    }
}

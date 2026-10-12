package com.moneymanager.localsettings

import java.util.prefs.Preferences
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class JavaPreferencesImportTest {
    private val root = Preferences.userRoot()
    private val nodePath = "/moneymanager-import-test-${Random.nextLong().toULong()}"

    @AfterTest
    fun cleanUp() {
        if (root.nodeExists(nodePath)) root.node(nodePath).removeNode()
    }

    @Test
    fun importsMissingKeysThenDeletesTheNode() {
        root.node(nodePath).apply {
            put(KEY_LAST_DATABASE, "/prefs/money.db")
            put(KEY_LAST_DIRECTORY, "/prefs")
        }
        val target = InMemorySettings(mutableMapOf(KEY_LAST_DIRECTORY to "/file"))

        importJavaPreferences(target, root, nodePath)

        assertEquals(mapOf(KEY_LAST_DATABASE to "/prefs/money.db", KEY_LAST_DIRECTORY to "/file"), target.values)
        assertFalse(root.nodeExists(nodePath))
    }

    @Test
    fun aMissingNodeIsANoOp() {
        val target = InMemorySettings(mutableMapOf())

        importJavaPreferences(target, root, nodePath)

        assertEquals(emptyMap(), target.values)
        assertFalse(root.nodeExists(nodePath))
    }

    private class InMemorySettings(
        val values: MutableMap<String, String>,
    ) : LocalSettings {
        override fun getString(key: String): String? = values[key]

        override fun putString(
            key: String,
            value: String,
        ) {
            values[key] = value
        }

        override fun remove(key: String) {
            values.remove(key)
        }
    }
}

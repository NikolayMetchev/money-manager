package com.moneymanager.localsettings

import co.touchlab.kermit.Logger
import com.moneymanager.files.appDataDirectory
import com.moneymanager.files.readText
import com.moneymanager.files.writeAtomically
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val logger = Logger.withTag("JsonLocalSettings")

/** `~/.moneymanager/settings.json`: the one settings file the desktop app and the native CLI share. */
fun defaultLocalSettingsPath(): Path = Path(appDataDirectory(), "settings.json")

/** Where [JsonLocalSettings] keeps its text: a file on desktop and native, a `localStorage` item in the browser. */
interface SettingsText {
    /** The text, or null when there is none yet. Throws [IOException] when it can't be read. */
    fun read(): String?

    /** Replaces the text in one step. Throws [IOException] when it can't. */
    fun write(text: String)
}

/** [SettingsText] in the file at [path], replaced atomically. */
class FileSettingsText(
    private val path: Path,
) : SettingsText {
    override fun read(): String? = if (SystemFileSystem.exists(path)) readText(path) else null

    override fun write(text: String) = writeAtomically(path, text.encodeToByteArray())

    override fun toString(): String = path.toString()
}

/**
 * [LocalSettings] stored as a flat JSON object in [text]. The desktop app and the native CLI share one file
 * ([defaultLocalSettingsPath]), so either one sees the database, remote bindings and vault path the other last chose.
 *
 * Another process (or browser tab) may write it at any time, so every access re-reads it (it's a few hundred bytes;
 * only a change in its text is re-parsed), and every write replaces it whole. Two writers within the same instant can
 * still lose one update; for settings that's acceptable. Like every [LocalSettings], access is best-effort and never
 * throws back to the caller.
 */
class JsonLocalSettings(
    private val text: SettingsText,
) : LocalSettings {
    private val lock = SynchronizedObject()
    private var cachedText: String? = null
    private var cached: Map<String, String> = emptyMap()

    override fun getString(key: String): String? = synchronized(lock) { current()[key] }

    override fun putString(
        key: String,
        value: String,
    ) {
        update { it + (key to value) }
    }

    override fun remove(key: String) {
        update { it - key }
    }

    /**
     * Stores every entry of [values] whose key isn't set yet, in one write. Returns false only when that write
     * failed, so a caller moving settings in from elsewhere knows whether it may delete the originals.
     */
    fun putMissing(values: Map<String, String>): Boolean = update { current -> values + current }

    private fun update(change: (Map<String, String>) -> Map<String, String>): Boolean =
        synchronized(lock) {
            val before = current()
            val after = change(before)
            if (after == before) return@synchronized true
            try {
                // Sorted so the file diffs cleanly between writes.
                val json = JsonObject(after.entries.sortedBy { it.key }.associate { it.key to JsonPrimitive(it.value) })
                val encoded = prettyJson.encodeToString(JsonObject.serializer(), json)
                text.write(encoded)
                cachedText = encoded
                cached = after
                true
            } catch (e: IOException) {
                logger.w(e) { "Could not write settings to $text" }
                false
            }
        }

    private fun current(): Map<String, String> {
        val read = readOrNull()
        if (read != cachedText) {
            cached = read?.let(::parse) ?: emptyMap()
            cachedText = read
        }
        return cached
    }

    private fun readOrNull(): String? =
        try {
            text.read()
        } catch (e: IOException) {
            logger.w(e) { "Could not read settings from $text" }
            null
        }

    private fun parse(json: String): Map<String, String> =
        try {
            Json.parseToJsonElement(json).jsonObject.mapValues { (key, value) ->
                val primitive = value.jsonPrimitive
                require(primitive.isString) { "Setting $key is not a string" }
                primitive.content
            }
        } catch (e: IllegalArgumentException) {
            // Malformed JSON (SerializationException is one), or a value that isn't a string.
            logger.w(e) { "Ignoring unreadable settings in $text" }
            emptyMap()
        }

    private companion object {
        val prettyJson = Json { prettyPrint = true }
    }
}

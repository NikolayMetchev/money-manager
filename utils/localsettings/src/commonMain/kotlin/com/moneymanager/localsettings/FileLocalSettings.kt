package com.moneymanager.localsettings

import co.touchlab.kermit.Logger
import com.moneymanager.files.appDataDirectory
import com.moneymanager.files.lastModifiedEpochMs
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

private val logger = Logger.withTag("FileLocalSettings")

/** `~/.moneymanager/settings.json`: the one settings file the desktop app and the native CLI share. */
fun defaultLocalSettingsPath(): Path = Path(appDataDirectory(), "settings.json")

/**
 * [LocalSettings] stored as a flat JSON object in [path]. The desktop app and the native CLI both use it,
 * so either one sees the database, remote bindings and vault path the other last chose.
 *
 * Another process may write the file at any time, so reads reload it whenever its modification time or size
 * changes, and every write re-reads it first and replaces it atomically. Two processes writing within the same
 * instant can still lose one update; for settings that's acceptable. Like every [LocalSettings], all access is
 * best-effort and never throws back to the caller.
 */
class FileLocalSettings(
    private val path: Path,
) : LocalSettings {
    private val lock = SynchronizedObject()
    private var cached: Map<String, String> = emptyMap()
    private var cachedStamp: FileStamp? = null

    override fun getString(key: String): String? = synchronized(lock) { current()[key] }

    override fun putString(
        key: String,
        value: String,
    ) = update { it + (key to value) }

    override fun remove(key: String) = update { it - key }

    private fun update(change: (Map<String, String>) -> Map<String, String>) {
        synchronized(lock) {
            val before = current()
            val after = change(before)
            if (after == before) return
            try {
                // Sorted so the file diffs cleanly between writes.
                val json = JsonObject(after.entries.sortedBy { it.key }.associate { it.key to JsonPrimitive(it.value) })
                writeAtomically(path, prettyJson.encodeToString(JsonObject.serializer(), json).encodeToByteArray())
                cached = after
                cachedStamp = stampOf()
            } catch (e: IOException) {
                logger.w(e) { "Could not write settings to $path" }
            }
        }
    }

    private fun current(): Map<String, String> {
        val stamp = stampOf()
        if (stamp != cachedStamp) {
            cached = stamp?.let { load() } ?: emptyMap()
            cachedStamp = stamp
        }
        return cached
    }

    private fun load(): Map<String, String> =
        try {
            Json.parseToJsonElement(readText(path)).jsonObject.mapValues { it.value.jsonPrimitive.content }
        } catch (e: IOException) {
            logger.w(e) { "Ignoring unreadable settings file $path" }
            emptyMap()
        } catch (e: IllegalArgumentException) {
            // Malformed JSON (SerializationException is one) or a non-object/non-string value.
            logger.w(e) { "Ignoring unreadable settings file $path" }
            emptyMap()
        }

    private fun stampOf(): FileStamp? =
        try {
            SystemFileSystem.metadataOrNull(path)?.let { FileStamp(lastModifiedEpochMs(path), it.size) }
        } catch (e: IOException) {
            logger.w(e) { "Could not stat settings file $path" }
            null
        }

    // Windows reports whole-second modification times, so the size catches most same-second rewrites too.
    private data class FileStamp(
        val lastModifiedEpochMs: Long,
        val size: Long,
    )

    private companion object {
        val prettyJson = Json { prettyPrint = true }
    }
}

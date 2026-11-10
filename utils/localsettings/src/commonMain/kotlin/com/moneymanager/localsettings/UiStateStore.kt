package com.moneymanager.localsettings

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Remembers view state (filters, selected tabs) across navigation and restarts, as JSON under
 * `ui.<name>` keys of a [LocalSettings]. A value that no longer decodes (an older shape, a removed
 * enum entry) reads as absent, so the view falls back to its default.
 */
class UiStateStore(
    private val settings: LocalSettings,
) {
    fun <T> get(
        key: String,
        serializer: KSerializer<T>,
    ): T? {
        val text = settings.getString(PREFIX + key) ?: return null
        return try {
            json.decodeFromString(serializer, text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun <T> put(
        key: String,
        serializer: KSerializer<T>,
        value: T,
    ) {
        settings.putString(PREFIX + key, json.encodeToString(serializer, value))
    }

    private companion object {
        const val PREFIX = "ui."
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** A [LocalSettings] that keeps its values in memory only: the default when no host provides one. */
class MemoryLocalSettings : LocalSettings {
    private val values = mutableMapOf<String, String>()

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

package com.moneymanager.localsettings

import java.util.prefs.Preferences

/**
 * JVM [LocalSettings] backed by [java.util.prefs.Preferences], so values survive application
 * restarts. No-arg by design: file-dialog composables construct it directly, outside DI.
 * All access is best-effort and never throws back to the caller.
 */
class JvmLocalSettings : LocalSettings {
    private val preferences: Preferences = Preferences.userNodeForPackage(JvmLocalSettings::class.java)

    override fun getString(key: String): String? =
        try {
            preferences.get(key, null)
        } catch (_: Exception) {
            null
        }

    override fun putString(
        key: String,
        value: String,
    ) {
        try {
            preferences.put(key, value)
        } catch (_: Exception) {
            // Persisting a preference is best-effort; never fail the operation over it.
        }
    }

    override fun remove(key: String) {
        try {
            preferences.remove(key)
        } catch (_: Exception) {
            // Best-effort.
        }
    }
}

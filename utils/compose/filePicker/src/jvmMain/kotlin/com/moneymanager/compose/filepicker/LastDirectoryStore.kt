package com.moneymanager.compose.filepicker

import java.io.File
import java.util.prefs.Preferences

/**
 * Remembers the directory last used in a file dialog so subsequent dialogs open there.
 * Backed by Java Preferences, so the choice also survives application restarts.
 * AWT's own file dialog (used since the in-process GTK chooser is disabled) does not
 * track recent locations itself.
 */
internal object LastDirectoryStore {
    private const val KEY = "lastDirectory"

    private val preferences: Preferences = Preferences.userNodeForPackage(LastDirectoryStore::class.java)

    /** Returns the last used directory if it still exists, or null. */
    fun load(): String? =
        try {
            preferences.get(KEY, null)?.takeIf { File(it).isDirectory }
        } catch (_: Exception) {
            null
        }

    /** Records the directory of the most recent successful file selection. */
    fun save(directory: String) {
        try {
            preferences.put(KEY, directory)
        } catch (_: Exception) {
            // Remembering the directory is best-effort; never fail the file operation over it
        }
    }
}

package com.moneymanager.localsettings

import co.touchlab.kermit.Logger
import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences

private val logger = Logger.withTag("DesktopLocalSettings")

/** Where the retired java.util.prefs store kept the desktop app's settings. */
private const val LEGACY_PREFERENCES_NODE = "/com/moneymanager/localsettings"

/**
 * The desktop app's [LocalSettings]: the settings file it shares with the native CLI. One instance per process,
 * so DI and the file dialogs constructed outside DI never race each other's writes. The first use moves any
 * settings an older version left in java.util.prefs into the file.
 */
val desktopLocalSettings: LocalSettings by lazy {
    JsonLocalSettings(FileSettingsText(defaultLocalSettingsPath())).also {
        importJavaPreferences(it, Preferences.userRoot(), LEGACY_PREFERENCES_NODE)
    }
}

/**
 * Moves every value in the java.util.prefs node [nodePath] that [target] doesn't already hold into it, then deletes
 * the node so the import runs once. The node is kept unless the file write succeeded, so a failed import is retried
 * next launch. Each key is read on its own: a node holding a value XML can't represent fails to flush, and one bad
 * key mustn't cost the others.
 */
internal fun importJavaPreferences(
    target: JsonLocalSettings,
    root: Preferences,
    nodePath: String,
) {
    try {
        if (!root.nodeExists(nodePath)) return
        val node = root.node(nodePath)
        val values =
            node.keys().mapNotNull { key ->
                try {
                    node.get(key, null)?.let { key to it }
                } catch (e: IllegalStateException) {
                    logger.w(e) { "Skipping unreadable preference $key" }
                    null
                }
            }
        if (!target.putMissing(values.toMap())) return
        node.removeNode()
        root.flush()
        logger.i { "Moved settings from java.util.prefs to the settings file" }
    } catch (e: BackingStoreException) {
        logger.w(e) { "Could not import java.util.prefs settings" }
    }
}

package com.moneymanager.localsettings

/**
 * Small cross-platform key/value store for app-level preferences that must live OUTSIDE any
 * money-manager database. Two things need this today: which database to open at startup (the
 * setting cannot be stored inside a database it is meant to select) and the directory last used
 * by a file dialog. Backed by java.util.prefs.Preferences on JVM and SharedPreferences on Android.
 */
interface LocalSettings {
    fun getString(key: String): String?

    fun putString(
        key: String,
        value: String,
    )

    fun remove(key: String)
}

/** Most recently opened database location (absolute path on JVM, database name on Android). */
const val KEY_LAST_DATABASE: String = "lastDatabase"

/** Directory last used in a file open/save dialog, so subsequent dialogs reopen there. */
const val KEY_LAST_DIRECTORY: String = "lastDirectory"

/**
 * Absolute path to a local directory the strategy catalog should read from instead of the published
 * GitHub Pages site (e.g. a freshly generated `strategy-library/` folder), so changes to built-in
 * strategies can be tested without publishing first. Absent/blank means use the published site.
 */
const val KEY_STRATEGY_CATALOG_LOCAL_DIRECTORY: String = "strategyCatalogLocalDirectory"

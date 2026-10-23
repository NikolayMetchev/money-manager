package com.moneymanager.localsettings

import kotlinx.browser.localStorage
import kotlinx.io.IOException

/** The `localStorage` item that holds the browser app's settings, as the same JSON the settings file holds. */
const val LOCAL_SETTINGS_STORAGE_KEY: String = "money-manager.settings"

/**
 * [SettingsText] in the browser's `localStorage` item [key]: per origin, so every tab of the app shares it, and
 * synchronous, as [LocalSettings] is.
 */
class LocalStorageText(
    private val key: String = LOCAL_SETTINGS_STORAGE_KEY,
) : SettingsText {
    override fun read(): String? = localStorage.getItem(key)

    @Suppress("TooGenericExceptionCaught") // Storage fails with a JavaScript error, which is no Exception.
    override fun write(text: String) {
        try {
            localStorage.setItem(key, text)
        } catch (failure: Throwable) {
            // Over the origin's quota, or storage turned off.
            throw IOException("Could not write localStorage item $key: ${failure.message}")
        }
    }

    override fun toString(): String = "localStorage item $key"
}

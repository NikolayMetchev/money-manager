package com.moneymanager.localsettings

import android.content.Context

/**
 * Android [LocalSettings] backed by [android.content.SharedPreferences], so values survive
 * application restarts. Construct with the application context (e.g. from DI).
 */
class AndroidLocalSettings(
    context: Context,
) : LocalSettings {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun putString(
        key: String,
        value: String,
    ) {
        preferences.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "money_manager_local_settings"
    }
}

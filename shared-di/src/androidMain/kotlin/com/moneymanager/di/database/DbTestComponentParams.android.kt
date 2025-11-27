package com.moneymanager.di.database

import android.content.Context

/**
 * Android-specific parameters for DbTestComponent creation.
 *
 * @property context The Android application context required for database initialization.
 */
actual class DbTestComponentParams(
    val context: Context,
)

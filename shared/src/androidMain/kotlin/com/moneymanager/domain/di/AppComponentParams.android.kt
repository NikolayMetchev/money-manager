package com.moneymanager.domain.di

import android.content.Context

/**
 * Android-specific parameters for AppComponent creation.
 *
 * @property context The Android application context required for database initialization.
 */
actual class AppComponentParams(
    val context: Context,
)

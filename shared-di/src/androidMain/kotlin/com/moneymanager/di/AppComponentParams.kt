package com.moneymanager.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider

actual data class AppComponentParams(val context: Context)

actual fun createTestAppComponentParams(): AppComponentParams = AppComponentParams(ApplicationProvider.getApplicationContext())

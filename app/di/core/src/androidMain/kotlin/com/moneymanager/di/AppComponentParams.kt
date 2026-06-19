package com.moneymanager.di

import android.content.Context
import com.moneymanager.remotestorage.googledrive.GoogleAccessTokenSource

actual data class AppComponentParams(
    val context: Context,
    /**
     * The Android Google Drive token source (native `AuthorizationClient`). Supplied by the app module
     * because the GMS consent flow needs an Activity-registered launcher, which can't live in this
     * library module (no manifest/Activity). See `MainActivity`.
     */
    val googleTokenSource: GoogleAccessTokenSource,
)

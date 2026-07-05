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
    /**
     * A second token source scoped to `drive.readonly`, used only by import directories: files the
     * user drops into a Drive folder aren't app-created, so [googleTokenSource]'s `drive.file` grant
     * can't see them. Kept separate so browsing imports never widens (or re-prompts) the DB-sync grant.
     */
    val googleDriveImportTokenSource: GoogleAccessTokenSource,
)

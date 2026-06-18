package com.moneymanager.remotestorage.googledrive

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Opens the OAuth consent URL in the system browser via an `ACTION_VIEW` intent. */
class AndroidBrowserLauncher(
    private val context: Context,
) : BrowserLauncher {
    override fun open(url: String) {
        val intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                // Launched from application context (no Activity), so a new task is required.
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
    }
}

package com.moneymanager.remotestorage.googledrive

import com.moneymanager.remotestorage.RemoteAuthException
import java.awt.Desktop
import java.net.URI

/** Opens the OAuth consent URL in the desktop's default browser via AWT [Desktop]. */
class DesktopBrowserLauncher : BrowserLauncher {
    override fun open(url: String) {
        val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
        if (desktop == null || !desktop.isSupported(Desktop.Action.BROWSE)) {
            throw RemoteAuthException("Can't open a browser on this system to sign in to Google Drive")
        }
        desktop.browse(URI(url))
    }
}

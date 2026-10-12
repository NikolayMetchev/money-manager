package com.moneymanager.remotestorage.googledrive

import platform.AppKit.NSWorkspace
import platform.Foundation.NSURL

internal actual fun openInSystemBrowser(url: String): Boolean {
    val nsUrl = NSURL.URLWithString(url) ?: return false
    return NSWorkspace.sharedWorkspace.openURL(nsUrl)
}

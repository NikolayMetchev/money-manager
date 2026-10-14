package com.moneymanager.di.params

import com.moneymanager.remotestorage.googledrive.BrowserLauncher
import com.moneymanager.remotestorage.googledrive.DesktopBrowserLauncher

actual class AppComponentParams(
    /** Opens the Google consent page: the desktop browser, or `PrintUrlBrowserLauncher` for a headless CLI. */
    val browserLauncher: BrowserLauncher = DesktopBrowserLauncher(),
)

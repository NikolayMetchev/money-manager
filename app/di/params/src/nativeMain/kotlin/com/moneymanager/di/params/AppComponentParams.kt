package com.moneymanager.di.params

import com.moneymanager.remotestorage.googledrive.BrowserLauncher

actual class AppComponentParams(
    /**
     * Opens the Google consent page. The CLI passes `SystemBrowserLauncher`, or `PrintUrlBrowserLauncher` when
     * there's no browser to open (headless or over SSH).
     */
    val browserLauncher: BrowserLauncher,
)

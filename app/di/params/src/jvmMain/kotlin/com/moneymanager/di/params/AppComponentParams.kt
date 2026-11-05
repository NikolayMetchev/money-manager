package com.moneymanager.di.params

import com.moneymanager.browser.BrowserLauncher
import com.moneymanager.browser.SystemBrowserLauncher

actual class AppComponentParams(
    /** Opens the Google consent page: the desktop browser, or `PrintUrlBrowserLauncher` for a headless CLI. */
    val browserLauncher: BrowserLauncher = SystemBrowserLauncher(),
)

package com.moneymanager.cli

import com.moneymanager.di.params.AppComponentParams
import com.moneymanager.remotestorage.googledrive.BrowserLauncher
import com.moneymanager.remotestorage.googledrive.SystemBrowserLauncher

internal actual fun cliComponentParams(browser: BrowserLauncher): AppComponentParams = AppComponentParams(browser)

internal actual fun systemBrowserLauncher(): BrowserLauncher = SystemBrowserLauncher()

package com.moneymanager.cli

import com.moneymanager.browser.BrowserLauncher
import com.moneymanager.di.params.AppComponentParams

internal actual fun cliComponentParams(browser: BrowserLauncher): AppComponentParams = AppComponentParams(browser)

package com.moneymanager.cli

import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.cryptodata.installCryptoCatalog
import com.moneymanager.database.DatabaseManager
import com.moneymanager.di.AppComponent
import com.moneymanager.di.params.AppComponentParams
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.files.appDataDirectory
import com.moneymanager.localsettings.LocalSettings
import com.moneymanager.remotestorage.googledrive.BrowserLauncher
import com.moneymanager.remotestorage.googledrive.PrintUrlBrowserLauncher
import com.moneymanager.remotestorage.sync.RemoteDatabaseController
import kotlinx.io.files.Path

/** The app-scoped services a command runs on: the [AppComponent]'s in production, test doubles in tests. */
internal class CliServices(
    val databaseManager: DatabaseManager,
    val localSettings: LocalSettings,
    val credentialVault: CredentialVault,
    val remoteController: RemoteDatabaseController?,
    val appVersion: AppVersion,
)

/** Where `mm` gets its services and writes its log file; tests swap both out. */
internal class CliPlatform(
    /** Builds the services; [browser] opens the Google consent page when a command needs a Drive sign-in. */
    val services: (browser: BrowserLauncher) -> CliServices,
    /** The browser to open for a Google sign-in, unless `--no-browser` asks for the URL to be printed. */
    val systemBrowser: () -> BrowserLauncher,
    /** The Info+ log file, or null for no file log. */
    val logFile: Path?,
) {
    fun browser(
        noBrowser: Boolean,
        print: (String) -> Unit,
    ): BrowserLauncher = if (noBrowser) PrintUrlBrowserLauncher(print) else systemBrowser()

    companion object {
        /** The real thing: the app graph, `~/.moneymanager/logs/mm.log` (beside the desktop app's own log). */
        fun system(): CliPlatform =
            CliPlatform(
                services = { browser ->
                    // Crypto names for imports; a failure is logged, not fatal, as in the desktop app.
                    runCatching { installCryptoCatalog() }
                    AppComponent.create(cliComponentParams(browser)).let { component ->
                        CliServices(
                            databaseManager = component.databaseManager,
                            localSettings = component.localSettings,
                            credentialVault = component.credentialVault,
                            remoteController = component.remoteDatabaseController,
                            appVersion = component.appVersion,
                        )
                    }
                },
                systemBrowser = ::systemBrowserLauncher,
                logFile = Path(appDataDirectory(), "logs", "mm.log"),
            )
    }
}

/** The platform's [AppComponentParams] carrying [browser]. */
internal expect fun cliComponentParams(browser: BrowserLauncher): AppComponentParams

/** Opens a URL in the OS's default browser. */
internal expect fun systemBrowserLauncher(): BrowserLauncher

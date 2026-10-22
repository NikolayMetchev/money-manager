package com.moneymanager.web

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import co.touchlab.kermit.Logger
import com.moneymanager.cryptodata.HttpCryptoCatalogRefresher
import com.moneymanager.cryptodata.installCryptoCatalog
import com.moneymanager.database.WorkerSqliteChannel
import com.moneymanager.di.AppComponent
import com.moneymanager.di.params.AppComponentParams
import com.moneymanager.session.DatabaseSessions
import com.moneymanager.ui.AppStartupHost
import com.moneymanager.ui.LocalBuildLabel
import com.moneymanager.ui.screens.apistrategy.LocalApiDownloadNotice
import kotlinx.browser.document

private val logger = Logger.withTag("Main")

/** Where :dist puts the database worker, relative to the page. */
private const val WORKER_URL = "node_modules/@moneymanager/sqlite-worker/worker.mjs"

private const val API_DOWNLOAD_NOTICE =
    "Most providers' APIs refuse requests from a web page, so downloads here usually fail. Download with the " +
        "desktop app or the mm command line instead, and keep the database on Google Drive to see it here."

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    logger.i { "Starting Money Manager in the browser" }
    runCatching { installCryptoCatalog() }
        .onFailure { logger.e(it) { "Failed to install crypto catalog" } }

    val params = AppComponentParams(sqliteChannel = WorkerSqliteChannel(WORKER_URL))
    val component = AppComponent.create(params)
    val sessions =
        DatabaseSessions(
            component.databaseManager,
            component.localSettings,
            component.credentialVault,
            component.remoteDatabaseController,
        )

    // loader.mjs records the build it started (wasmJs or js).
    val target = document.documentElement?.getAttribute("data-target")
    ComposeViewport(checkNotNull(document.body)) {
        CompositionLocalProvider(
            LocalBuildLabel provides target,
            LocalApiDownloadNotice provides API_DOWNLOAD_NOTICE,
        ) {
            AppStartupHost(
                databaseManager = component.databaseManager,
                appVersion = component.appVersion,
                sessions = sessions,
                onInfoLog = { message -> logger.i { message } },
                onErrorLog = { message, error -> logger.e(error) { message } },
                remoteController = component.remoteDatabaseController,
                strategySyncController = component.strategySyncController,
                strategyCatalogController = component.strategyCatalogController,
                cryptoCatalogRefresher = HttpCryptoCatalogRefresher(),
                credentialVault = component.credentialVault,
                credentialSyncController = component.credentialSyncController,
            )
        }
    }
}

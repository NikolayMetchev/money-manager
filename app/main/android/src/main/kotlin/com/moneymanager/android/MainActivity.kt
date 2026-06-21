package com.moneymanager.android

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.moneymanager.android.auth.AndroidGoogleAccessTokenSource
import com.moneymanager.android.auth.GoogleAuthConsentLauncher
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.di.AppComponent
import com.moneymanager.di.AppComponentParams
import com.moneymanager.di.database.DatabaseComponent
import com.moneymanager.di.database.createImportEngine
import com.moneymanager.di.database.toApplication
import com.moneymanager.di.initializeVersionReader
import com.moneymanager.importengineapi.EditingLockedException
import com.moneymanager.remotestorage.sync.RemoteDatabaseController
import com.moneymanager.remotestorage.sync.SyncResult
import com.moneymanager.ui.AppStartupHost
import com.moneymanager.ui.error.GlobalSchemaErrorState
import com.moneymanager.ui.error.SchemaErrorDetector
import com.moneymanager.ui.toAppServices
import kotlinx.coroutines.runBlocking

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {
    private var remoteController: RemoteDatabaseController? = null
    private var openDatabase: MoneyManagerDatabaseWrapper? = null

    // Bridges the Google Drive (AuthorizationClient) consent sheet to a suspend call; must be registered
    // before the Activity is STARTED, so it's attached in onCreate and detached in onDestroy.
    private val googleAuthConsentLauncher = GoogleAuthConsentLauncher()

    /**
     * Ensures the remote copy is current, uploading only if the database changed since the last sync.
     * Returns true if the remote is up to date afterwards (nothing to push, or the push succeeded).
     */
    private fun ensureRemoteUpToDate(): Boolean {
        val controller = remoteController ?: return false
        val database = openDatabase ?: return false
        if (!controller.hasActiveSession()) return false
        return runCatching {
            runBlocking {
                // Guarded push: a BLOCKED result (another device pushed) means the remote is NOT up to
                // date from our side, so the local copy must be kept rather than deleted.
                !controller.hasUnsyncedChanges(database) || controller.syncNow(database) == SyncResult.UPLOADED
            }
        }.onFailure { Log.e(TAG, "Failed to sync database", it) }.getOrDefault(false)
    }

    override fun onStop() {
        // Keep the remote copy current when backgrounded. The local copy is NOT removed here: onStop
        // also fires on a simple app switch, and the open database must survive a return to foreground.
        ensureRemoteUpToDate()
        super.onStop()
    }

    override fun onDestroy() {
        googleAuthConsentLauncher.detach()
        // A true "close": when finishing, push any changes and drop the local working copy so only the
        // encrypted remote copy is kept between runs.
        val controller = remoteController
        if (isFinishing && controller?.hasActiveSession() == true) {
            val remoteUpToDate = ensureRemoteUpToDate()
            openDatabase?.close()
            if (remoteUpToDate) {
                runCatching { runBlocking { controller.deleteLocalCache() } }
                    .onSuccess { Log.i(TAG, "Deleted local working copy; cloud copy is up to date") }
                    .onFailure { Log.e(TAG, "Failed to delete local database on close", it) }
            } else {
                Log.w(TAG, "Kept local database: cloud sync failed, so the local copy is the only safe copy")
            }
        }
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (SchemaErrorDetector.isSchemaError(throwable)) {
                Log.e(TAG, "Schema error detected: ${throwable.message}", throwable)
                GlobalSchemaErrorState.reportError(
                    databaseLocation = "default",
                    error = throwable,
                )
            } else {
                Log.e(TAG, "Uncaught exception on thread ${thread.name}: ${throwable.message}", throwable)
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }

        initializeVersionReader(applicationContext)

        googleAuthConsentLauncher.attach(this)
        val params =
            AppComponentParams(
                context = applicationContext,
                googleTokenSource = AndroidGoogleAccessTokenSource(applicationContext, googleAuthConsentLauncher),
            )
        val component: AppComponent = AppComponent.create(params)
        val controller = component.remoteDatabaseController
        remoteController = controller

        setContent {
            AppStartupHost(
                databaseManager = component.databaseManager,
                appVersion = component.appVersion,
                localSettings = component.localSettings,
                createAppServices = { database ->
                    val component = DatabaseComponent.create(database)
                    val importEngine =
                        component.createImportEngine(
                            editGate = {
                                if (controller.syncState.value.editingLocked) throw EditingLockedException()
                            },
                        )
                    component.toApplication().toAppServices(importEngine)
                },
                onInfoLog = { message -> Log.i(TAG, message) },
                onErrorLog = { message, error -> Log.e(TAG, message, error) },
                remoteController = controller,
                onDatabaseReady = { database, _ -> openDatabase = database },
            )
        }
    }
}

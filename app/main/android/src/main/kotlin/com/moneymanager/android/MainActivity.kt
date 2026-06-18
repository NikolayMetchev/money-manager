package com.moneymanager.android

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.moneymanager.database.DatabaseManager
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.di.AppComponent
import com.moneymanager.di.AppComponentParams
import com.moneymanager.di.database.DatabaseComponent
import com.moneymanager.di.database.toApplication
import com.moneymanager.di.initializeVersionReader
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.remotestorage.sync.RemoteDatabaseController
import com.moneymanager.ui.AppStartupHost
import com.moneymanager.ui.error.GlobalSchemaErrorState
import com.moneymanager.ui.error.SchemaErrorDetector
import com.moneymanager.ui.toAppServices
import kotlinx.coroutines.runBlocking

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {
    private var databaseManager: DatabaseManager? = null
    private var remoteController: RemoteDatabaseController? = null
    private var openDatabase: MoneyManagerDatabaseWrapper? = null
    private var openLocation: DbLocation? = null

    /** Pushes the open cloud-backed database to its remote backing. Returns true on success. */
    private fun pushToRemoteIfActive(): Boolean {
        val controller = remoteController ?: return false
        val database = openDatabase ?: return false
        if (!controller.hasActiveSession()) return false
        return runCatching { runBlocking { controller.syncNow(database) } }
            .onFailure { Log.e(TAG, "Failed to sync database", it) }
            .isSuccess
    }

    override fun onStop() {
        // Keep the remote copy current when backgrounded. The local copy is NOT removed here: onStop
        // also fires on a simple app switch, and the open database must survive a return to foreground.
        pushToRemoteIfActive()
        super.onStop()
    }

    override fun onDestroy() {
        // A true "close": when finishing, push once more and drop the local working copy so only the
        // encrypted remote copy is kept between runs.
        if (isFinishing && remoteController?.hasActiveSession() == true) {
            val pushed = pushToRemoteIfActive()
            openDatabase?.close()
            if (pushed) {
                openLocation?.let { location ->
                    runCatching { runBlocking { databaseManager?.deleteDatabase(location) } }
                        .onFailure { Log.e(TAG, "Failed to delete local database on close", it) }
                }
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

        val params = AppComponentParams(context = applicationContext)
        val component: AppComponent = AppComponent.create(params)
        databaseManager = component.databaseManager
        val controller =
            RemoteDatabaseController(component.remoteDatabaseSyncService, component.remoteStorageProviderFactory)
        remoteController = controller

        setContent {
            AppStartupHost(
                databaseManager = component.databaseManager,
                appVersion = component.appVersion,
                localSettings = component.localSettings,
                createAppServices = { database ->
                    DatabaseComponent.create(database).toApplication().toAppServices()
                },
                onInfoLog = { message -> Log.i(TAG, message) },
                onErrorLog = { message, error -> Log.e(TAG, message, error) },
                remoteController = controller,
                onDatabaseReady = { database, location ->
                    openDatabase = database
                    openLocation = location
                },
            )
        }
    }
}

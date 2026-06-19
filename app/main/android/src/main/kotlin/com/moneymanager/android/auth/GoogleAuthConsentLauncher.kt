package com.moneymanager.android.auth

import android.app.PendingIntent
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.moneymanager.remotestorage.RemoteAuthException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bridges the GMS consent `PendingIntent` — returned by `AuthorizationClient` when sign-in needs user
 * interaction — into a suspend call.
 *
 * The launcher must be registered before the Activity is STARTED, so [attach] is called from
 * `MainActivity.onCreate` and [detach] from `onDestroy`. The token source calls [launch] and awaits the
 * result `Intent` (null = the user cancelled / no data returned).
 */
class GoogleAuthConsentLauncher {
    private var launcher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var pending: CompletableDeferred<Intent?>? = null

    fun attach(activity: ComponentActivity) {
        launcher =
            activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
                pending?.complete(result.data)
                pending = null
            }
    }

    fun detach() {
        launcher = null
        pending?.cancel()
        pending = null
    }

    suspend fun launch(pendingIntent: PendingIntent): Intent? {
        val activeLauncher = launcher ?: throw RemoteAuthException("Google sign-in UI is not ready")
        val deferred = CompletableDeferred<Intent?>()
        pending = deferred
        withContext(Dispatchers.Main) {
            activeLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
        }
        return deferred.await()
    }
}

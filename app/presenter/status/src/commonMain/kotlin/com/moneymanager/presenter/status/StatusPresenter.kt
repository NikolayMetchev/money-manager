package com.moneymanager.presenter.status

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.credentialvault.CredentialVaultLockedException
import com.moneymanager.credentialvault.VaultState
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.remotestorage.sync.RemoteDatabaseController
import com.moneymanager.remotestorage.sync.SyncResult
import com.moneymanager.remotestorage.sync.SyncState
import com.moneymanager.session.DatabaseSession
import com.moneymanager.session.bindingFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

private val LOCAL_CHANGES_INTERVAL = 2.seconds

sealed interface StatusEvent {
    /** Arms the cloud session with the cloud database's [password], then checks the cloud for other devices' changes. */
    data class Connect(
        val password: String,
    ) : StatusEvent

    /** Asks the cloud whether another device pushed since this one last synced. */
    data object CheckRemote : StatusEvent

    /** Uploads the local copy; [force] overwrites a cloud copy another device changed. */
    data class Upload(
        val force: Boolean = false,
    ) : StatusEvent

    /** Asks for the vault password (or where to create the vault) through whoever answers the vault's requests. */
    data object UnlockVault : StatusEvent

    data object LockVault : StatusEvent
}

data class StatusMessage(
    val text: String,
    val error: Boolean,
)

/** The cloud copy the open database is the working copy of. */
data class CloudStatus(
    val remoteName: String,
    val providerId: String,
    /** True once the cloud password is known this run, so the cloud can be checked and written. */
    val connected: Boolean,
    val sync: SyncState,
    /** Edits since the last upload, known even before connecting (from the kept copy's baseline). */
    val localChanges: Boolean,
)

/**
 * What a status screen shows: the open database at [location], its [cloud] copy (null when it isn't cloud-backed),
 * the credential [vault], and what's under way ([busy]) or just happened ([message]).
 */
data class StatusModel(
    val version: AppVersion,
    val location: DbLocation,
    val cloud: CloudStatus?,
    val vault: VaultState,
    val busy: String?,
    val message: StatusMessage?,
    val eventSink: (StatusEvent) -> Unit,
)

/**
 * The open [session]'s standing and the actions on it. Actions run on [scope], which must outlive the screen, so
 * an upload keeps going when the person looks elsewhere. Downloading isn't one of them: it closes and reopens the
 * database, which only whoever owns the session can do.
 */
@Composable
fun presentStatus(
    session: DatabaseSession,
    remote: RemoteDatabaseController?,
    vault: CredentialVault,
    appVersion: AppVersion,
    scope: CoroutineScope,
): StatusModel {
    val vaultState by vault.state.collectAsState()
    val syncState by (remote?.syncState ?: NO_REMOTE).collectAsState()
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<StatusMessage?>(null) }
    // The binding and the armed session aren't observable; an action bumps this to read them again.
    var revision by remember { mutableIntStateOf(0) }
    val binding = remember(remote, session, revision) { remote?.bindingFor(session.location) }
    val connected = remember(remote, revision) { remote?.hasActiveSession() == true }
    val localChanges = pollLocalChanges(remote, session, bound = binding != null, connected = connected, syncState = syncState)

    val eventSink: (StatusEvent) -> Unit =
        remember(session, remote, vault, scope) {
            fun run(
                label: String,
                block: suspend () -> StatusMessage?,
            ) {
                if (busy != null) return
                busy = label
                message = null
                scope.launch {
                    try {
                        message = block()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (expected: Exception) {
                        // Offline, signed out, a wrong vault password: the person is told and can try again.
                        message = StatusMessage("$label failed: ${expected.message ?: expected::class.simpleName}", error = true)
                    } finally {
                        busy = null
                        revision++
                    }
                }
            }

            { event ->
                when (event) {
                    is StatusEvent.Connect ->
                        run("Connecting") {
                            val controller = checkNotNull(remote)
                            if (controller.resume(event.password)) {
                                controller.checkRemote(session.database)
                                null
                            } else {
                                StatusMessage("That password doesn't open the cloud copy.", error = true)
                            }
                        }
                    StatusEvent.CheckRemote ->
                        run("Checking the cloud") {
                            checkNotNull(remote).checkRemote(session.database)
                            null
                        }
                    is StatusEvent.Upload ->
                        run("Uploading") {
                            when (checkNotNull(remote).syncNow(session.database, force = event.force)) {
                                SyncResult.UPLOADED -> StatusMessage("Uploaded.", error = false)
                                SyncResult.BLOCKED ->
                                    StatusMessage(
                                        "Another device changed the cloud copy since this one last synced: download it, or overwrite it.",
                                        error = true,
                                    )
                                SyncResult.NO_SESSION -> StatusMessage("Connect to the cloud copy first.", error = true)
                            }
                        }
                    StatusEvent.UnlockVault ->
                        run("Unlocking the vault") {
                            try {
                                vault.requireUnlocked("Unlock the credential vault")
                            } catch (_: CredentialVaultLockedException) {
                                // The person cancelled the prompt: nothing to report.
                            }
                            null
                        }
                    StatusEvent.LockVault ->
                        run("Locking the vault") {
                            vault.lock()
                            null
                        }
                }
            }
        }

    val cloud =
        binding?.let {
            CloudStatus(
                remoteName = it.remoteName,
                providerId = it.providerId,
                connected = connected,
                sync = syncState,
                localChanges = localChanges,
            )
        }
    return StatusModel(appVersion, session.location, cloud, vaultState, busy, message, eventSink)
}

/**
 * Whether the bound copy has edits since its last upload, read every few seconds (a local query, no network). Once
 * connected, [RemoteDatabaseController.syncState] carries it, so the answer comes from that one place.
 */
@Composable
private fun pollLocalChanges(
    remote: RemoteDatabaseController?,
    session: DatabaseSession,
    bound: Boolean,
    connected: Boolean,
    syncState: SyncState,
): Boolean {
    var unconnectedChanges by remember(session) { mutableStateOf(false) }
    LaunchedEffect(remote, session, bound, connected) {
        if (remote == null || !bound) return@LaunchedEffect
        while (true) {
            if (connected) {
                runCatching { remote.refreshLocalDirty(session.database) }
            } else {
                unconnectedChanges = runCatching { remote.hasUnsyncedChanges(session.database) }.getOrDefault(false)
            }
            delay(LOCAL_CHANGES_INTERVAL)
        }
    }
    return if (connected) syncState.canUpload else unconnectedChanges
}

private val NO_REMOTE = MutableStateFlow(SyncState())

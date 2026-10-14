package com.moneymanager.session

import co.touchlab.kermit.Logger
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.remotestorage.sync.RemoteDatabaseController
import com.moneymanager.remotestorage.sync.SyncResult
import com.moneymanager.tasks.Progress

private val logger = Logger.withTag("CloudClose")

/** The defaults a front end offers when closing a cloud-backed database (the desktop dialog, the CLI prompt). */
data class CloseDecision(
    val remoteName: String,
    /** True when the local copy changed since its last upload (or that can't be told), so uploading is the default. */
    val localChanged: Boolean,
    /** True when no cloud session is armed, so uploading needs the database password. */
    val needsPassword: Boolean,
)

/** What closing a cloud-backed database did; [uploaded] is false when nothing was uploaded or the push was refused. */
data class CloseOutcome(
    val syncResult: SyncResult?,
    val keptLocal: Boolean,
) {
    val uploaded: Boolean get() = syncResult == SyncResult.UPLOADED
}

/** The close defaults for [database], or null when it isn't cloud-backed and simply closes. */
suspend fun RemoteDatabaseController.closeDecision(database: MoneyManagerDatabaseWrapper): CloseDecision? {
    val binding = activeBinding() ?: return null
    val baselineKnown = hasSyncBaseline()
    val changed = runCatching { hasUnsyncedChanges(database) }.getOrDefault(true)
    return CloseDecision(
        remoteName = binding.remoteName,
        // Assume changed when we have no baseline, so the upload is offered by default.
        localChanged = !baselineKnown || changed,
        needsPassword = !hasActiveSession(),
    )
}

/**
 * Applies the user's close choices for a cloud-backed database: optionally arms a session (when the
 * database was opened straight from a kept local copy, [password] is required) and uploads, then
 * closes the connection and — only when the user chose not to keep the local copy AND the remote is
 * up to date — deletes the local working copy. The local copy is never dropped while it holds
 * unsynced changes.
 */
suspend fun RemoteDatabaseController.performClose(
    database: MoneyManagerDatabaseWrapper,
    upload: Boolean,
    keepLocal: Boolean,
    password: String?,
    localChanged: Boolean,
    onProgress: (Progress) -> Unit = {},
): CloseOutcome {
    var remoteCurrent = false
    var syncResult: SyncResult? = null
    if (upload) {
        onProgress(Progress.counted("Checking for changes…", 0, 100))
        val ready =
            hasActiveSession() ||
                (password != null && runCatching { resume(password) }.getOrDefault(false))
        if (ready) {
            // Guarded push: if another device pushed since our last sync the upload is refused
            // (BLOCKED) rather than clobbering it — then we keep the local copy.
            syncResult =
                runCatching { syncNow(database, rebuildViews = false, onProgress = onProgress) }
                    .onFailure { logger.e(it) { "Failed to sync database on close" } }
                    .getOrNull()
            remoteCurrent = syncResult == SyncResult.UPLOADED
        } else {
            logger.w { "Could not arm cloud session on close (wrong password?); keeping local copy" }
        }
    } else if (!localChanged && !keepLocal) {
        // About to drop the only local copy without uploading: confirm the remote archive still exists
        // and is reachable first. A "no local changes" baseline doesn't guarantee the remote wasn't
        // deleted externally since our last sync — without this check we'd close with no recoverable copy.
        onProgress(Progress.counted("Checking cloud copy…", 0, 100))
        remoteCurrent =
            runCatching { remoteArchiveSize() != null }
                .onFailure { logger.e(it) { "Failed to verify remote copy on close; keeping local copy" } }
                .getOrDefault(false)
    }
    onProgress(Progress.counted("Finishing…", 95, 100))
    database.close()
    var keptLocal = true
    when {
        keepLocal -> Unit
        remoteCurrent ->
            runCatching { deleteLocalCache() }
                .onSuccess {
                    keptLocal = false
                    logger.i { "Deleted local working copy; cloud copy is up to date" }
                }.onFailure { logger.e(it) { "Failed to delete local database on close" } }
        else -> logger.w { "Kept local database despite delete request: cloud copy is not up to date or unverified" }
    }
    return CloseOutcome(syncResult, keptLocal)
}

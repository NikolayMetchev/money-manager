package com.moneymanager.remotestorage.sync

/**
 * Where the local working copy stands relative to the remote archive, for a remote-backed database.
 *
 * - [NO_SESSION] – not remote-backed, or no password entered this run.
 * - [IN_SYNC] – local matches remote; nothing to do.
 * - [LOCAL_AHEAD] – local has unpushed edits; remote unchanged → safe to upload.
 * - [REMOTE_AHEAD] – another device pushed; local has no edits → download to catch up, editing locked.
 * - [CONFLICT] – both sides changed since the last sync → editing locked; uploading or downloading
 *   discards one side's changes (the UI confirms before either).
 */
enum class SyncStatus { NO_SESSION, IN_SYNC, LOCAL_AHEAD, REMOTE_AHEAD, CONFLICT }

/**
 * Reactive snapshot of the active remote-backed database's sync standing, published by
 * [RemoteDatabaseController.syncState]. Detection is on demand (a "Check remote for changes" button
 * and the upload guard), not polled.
 */
data class SyncState(
    val status: SyncStatus = SyncStatus.NO_SESSION,
    val localDirty: Boolean = false,
    val remoteChanged: Boolean = false,
    /** True while a remote check / sync operation is in flight (for spinners and disabling buttons). */
    val busy: Boolean = false,
) {
    /** Editing must be blocked while the remote is ahead, until the user downloads. */
    val editingLocked: Boolean get() = status == SyncStatus.REMOTE_AHEAD || status == SyncStatus.CONFLICT

    /** The Upload button is meaningful only when there are local changes to push. */
    val canUpload: Boolean get() = status == SyncStatus.LOCAL_AHEAD || status == SyncStatus.CONFLICT

    /** The Download button is meaningful only when the remote has moved ahead. */
    val canDownload: Boolean get() = status == SyncStatus.REMOTE_AHEAD || status == SyncStatus.CONFLICT

    companion object {
        /** Derives the [SyncStatus] from the two change flags (assuming an active session). */
        fun statusFor(
            localDirty: Boolean,
            remoteChanged: Boolean,
        ): SyncStatus =
            when {
                localDirty && remoteChanged -> SyncStatus.CONFLICT
                remoteChanged -> SyncStatus.REMOTE_AHEAD
                localDirty -> SyncStatus.LOCAL_AHEAD
                else -> SyncStatus.IN_SYNC
            }
    }
}

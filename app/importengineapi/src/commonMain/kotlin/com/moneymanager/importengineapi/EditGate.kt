package com.moneymanager.importengineapi

/**
 * A gate the [ImportEngine] consults before performing any write. It lets the app block all edits and
 * imports when they would be unsafe — e.g. a cloud-backed database whose remote copy has been changed
 * by another device, where a local write would diverge from the remote (see the remote-storage sync
 * layer). Database-free on purpose so the engine modules don't depend on the sync/DI layers; the real
 * implementation is wired in where the sync state is available.
 */
fun interface EditGate {
    /** Throws [EditingLockedException] if writes are currently blocked; returns normally otherwise. */
    fun ensureWritable()

    companion object {
        /** A gate that never blocks — the default for imports/tests with no remote lock in play. */
        val AlwaysWritable: EditGate = EditGate {}
    }
}

/** Thrown by [EditGate.ensureWritable] when editing is locked (e.g. the remote copy is ahead). */
class EditingLockedException(
    message: String = "Editing is locked: download the latest changes from cloud storage first.",
) : Exception(message)

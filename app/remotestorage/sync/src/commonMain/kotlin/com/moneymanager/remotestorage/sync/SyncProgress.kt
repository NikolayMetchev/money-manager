package com.moneymanager.remotestorage.sync

/**
 * Coarse progress for a sync operation — uploading (shrink → compact → encrypt → upload) or
 * downloading (download → decrypt → decompress → rebuild). [fraction] is in 0f..1f.
 */
data class SyncProgress(
    val message: String,
    val fraction: Float,
)

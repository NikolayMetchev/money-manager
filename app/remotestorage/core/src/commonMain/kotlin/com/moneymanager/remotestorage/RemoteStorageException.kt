package com.moneymanager.remotestorage

/** Base type for failures surfaced by a [RemoteStorageProvider]. */
open class RemoteStorageException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Sign-in failed or was cancelled by the user. */
class RemoteAuthException(
    message: String,
    cause: Throwable? = null,
) : RemoteStorageException(message, cause)

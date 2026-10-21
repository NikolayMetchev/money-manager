package com.moneymanager.credentialvault

import kotlinx.browser.localStorage
import kotlinx.io.IOException
import kotlin.io.encoding.Base64

/**
 * [VaultStorage] in the browser's `localStorage`, one item per vault path, its (already encrypted) bytes in Base64.
 * A vault is a few kilobytes, well inside the origin's quota, and `localStorage` is synchronous, as [VaultStorage] is.
 * Clearing the site's data deletes it, as deleting the file does elsewhere.
 */
class LocalStorageVaultStorage : VaultStorage {
    override fun exists(path: String): Boolean = localStorage.getItem(key(path)) != null

    override fun read(path: String): ByteArray = Base64.decode(localStorage.getItem(key(path)) ?: throw IOException("No vault at $path"))

    @Suppress("TooGenericExceptionCaught") // Storage fails with a JavaScript error, which is no Exception.
    override fun writeAtomically(
        path: String,
        bytes: ByteArray,
    ) {
        try {
            // One setItem replaces the item whole, so a failure leaves the previous vault.
            localStorage.setItem(key(path), Base64.encode(bytes))
        } catch (failure: Throwable) {
            throw IOException("Could not write the vault at $path: ${failure.message}")
        }
    }

    override fun delete(path: String) {
        localStorage.removeItem(key(path))
    }

    private fun key(path: String) = "money-manager.vault:$path"
}

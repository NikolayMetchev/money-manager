package com.moneymanager.credentialvault

import com.moneymanager.files.absolutePath
import com.moneymanager.files.isRegularFile
import com.moneymanager.files.readBytes
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import com.moneymanager.files.writeAtomically as writeFileAtomically

/** [VaultStorage] over the local file system. */
class FileVaultStorage : VaultStorage {
    override fun exists(path: String): Boolean = isRegularFile(Path(path))

    override fun read(path: String): ByteArray = readBytes(Path(path))

    override fun writeAtomically(
        path: String,
        bytes: ByteArray,
    ) = writeFileAtomically(Path(path), bytes)

    override fun delete(path: String) {
        SystemFileSystem.delete(Path(path), mustExist = false)
    }
}

/** The vault file name used when the user hasn't chosen another location. */
const val DEFAULT_VAULT_FILE_NAME: String = "money-manager.credentials"

/**
 * Resolves the default vault path next to a database file. Directory-level rather than per-database, so
 * flows that pick the database later (opening one from cloud storage) can still bind up front, and
 * databases kept in the same folder share one vault.
 */
fun vaultPathBesideDatabaseFile(databasePath: String): String {
    val parent = absolutePath(Path(databasePath)).parent ?: absolutePath(Path("."))
    return Path(parent, DEFAULT_VAULT_FILE_NAME).toString()
}

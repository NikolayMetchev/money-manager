package com.moneymanager.credentialvault

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/** [VaultStorage] over the local file system. */
class FileVaultStorage : VaultStorage {
    override fun exists(path: String): Boolean = Files.isRegularFile(Paths.get(path))

    override fun read(path: String): ByteArray = Files.readAllBytes(Paths.get(path))

    override fun writeAtomically(
        path: String,
        bytes: ByteArray,
    ) {
        val target = Paths.get(path).toAbsolutePath()
        target.parent?.let(Files::createDirectories)
        // Write beside the target and rename over it, so a crash mid-write leaves the old vault intact.
        val temp = Files.createTempFile(target.parent ?: Path.of("."), target.fileName.toString(), ".tmp")
        try {
            Files.write(temp, bytes)
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    override fun delete(path: String) {
        Files.deleteIfExists(Paths.get(path))
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
    val parent = Paths.get(databasePath).toAbsolutePath().parent ?: Paths.get(".").toAbsolutePath()
    return parent.resolve(DEFAULT_VAULT_FILE_NAME).toString()
}

actual fun fileVaultStorage(): VaultStorage = FileVaultStorage()

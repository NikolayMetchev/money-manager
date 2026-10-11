package com.moneymanager.credentialvault

import com.moneymanager.files.absolutePath
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileVaultStorageTest {
    private val storage = FileVaultStorage()
    private val dir = Path(absolutePath(SystemTemporaryDirectory), "vault-test-${Random.nextLong().toULong()}")
    private val vault = Path(dir, DEFAULT_VAULT_FILE_NAME).toString()

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(dir)) {
            SystemFileSystem.list(dir).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun `writes replace the vault and leave no temp file behind`() {
        storage.writeAtomically(vault, byteArrayOf(1, 2, 3))
        storage.writeAtomically(vault, byteArrayOf(9))

        assertTrue(storage.exists(vault))
        assertContentEquals(byteArrayOf(9), storage.read(vault))
        assertEquals(listOf(DEFAULT_VAULT_FILE_NAME), SystemFileSystem.list(dir).map { it.name })
    }

    @Test
    fun `deleting removes the vault and a missing vault is a no-op`() {
        storage.writeAtomically(vault, byteArrayOf(1))

        storage.delete(vault)
        storage.delete(vault)

        assertFalse(storage.exists(vault))
    }

    @Test
    fun `a directory is not a vault`() {
        SystemFileSystem.createDirectories(dir)

        assertFalse(storage.exists(dir.toString()))
    }

    @Test
    fun `the default vault sits beside the database`() {
        assertEquals(Path(dir, DEFAULT_VAULT_FILE_NAME).toString(), vaultPathBesideDatabaseFile(Path(dir, "money_manager.db").toString()))

        val relative = Path(vaultPathBesideDatabaseFile("money_manager.db"))
        assertTrue(relative.isAbsolute)
        assertEquals(DEFAULT_VAULT_FILE_NAME, relative.name)
    }
}

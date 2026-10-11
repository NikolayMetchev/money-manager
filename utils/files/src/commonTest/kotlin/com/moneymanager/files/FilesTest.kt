package com.moneymanager.files

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

class FilesTest {
    // Native falls back to a relative temp directory when TMPDIR is unset.
    private val dir = Path(absolutePath(SystemTemporaryDirectory), "files-test-${Random.nextLong().toULong()}")

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(dir)) {
            SystemFileSystem.list(dir).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun expandsOnlyALeadingTilde() {
        val home = checkNotNull(userHomeDirectory())
        assertEquals(home, expandTilde("~"))
        assertEquals("$home/imports", expandTilde("~/imports"))
        assertEquals("~other/imports", expandTilde("~other/imports"))
        assertEquals("/data/~/x", expandTilde("/data/~/x"))
    }

    @Test
    fun writeAtomicallyCreatesThenReplacesAndLeavesNoTempFile() {
        val file = Path(dir, "nested", "vault.bin")
        writeAtomically(file, byteArrayOf(1, 2, 3))
        writeAtomically(file, byteArrayOf(4, 5))

        assertContentEquals(byteArrayOf(4, 5), readBytes(file))
        assertEquals(listOf("vault.bin"), SystemFileSystem.list(file.parent!!).map { it.name })
        SystemFileSystem.delete(file)
    }

    @Test
    fun textRoundTripsAndCreatesParents() {
        val file = Path(dir, "coins.tsv")
        writeText(file, "BTC\tBitcoin\n")

        assertEquals("BTC\tBitcoin\n", readText(file))
        assertTrue(isRegularFile(file))
        assertFalse(isDirectory(file))
        assertTrue(isDirectory(dir))
        assertTrue(lastModifiedEpochMs(file) > 0)
    }

    @Test
    fun absolutePathKeepsAbsoluteAndResolvesRelative() {
        assertEquals(dir, absolutePath(dir))
        val relative = absolutePath(Path("missing.db"))
        assertTrue(relative.isAbsolute)
        assertEquals("missing.db", relative.name)
    }
}

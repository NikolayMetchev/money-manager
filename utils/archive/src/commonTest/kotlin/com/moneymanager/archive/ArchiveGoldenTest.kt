package com.moneymanager.archive

import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Archives are stored on disk and on Drive, and the desktop app and the native CLI must read each
 * other's. Every platform runs this test, so these fixtures (one packed by the JVM with `java.util.zip`
 * and the JDK provider, one packed by linuxX64 with the platform zlib and openssl3) prove both
 * directions. Never regenerate them: a fixture that stops decoding means the format changed.
 */
class ArchiveGoldenTest {
    @Test
    fun `an archive packed on the JVM unpacks here`() =
        runTest {
            assertContentEquals(payload(), ArchiveCodec.unpack(Base64.decode(JVM_ARCHIVE.filterNot(Char::isWhitespace)), PASSWORD))
        }

    @Test
    fun `an archive packed on native unpacks here`() =
        runTest {
            assertContentEquals(payload(), ArchiveCodec.unpack(Base64.decode(NATIVE_ARCHIVE.filterNot(Char::isWhitespace)), PASSWORD))
        }

    @Test
    fun `compression writes a zlib stream that round-trips across several buffers`() {
        val payload = payload()
        val compressed = deflate(payload)

        // CMF 0x78 (deflate, 32 KiB window) and a header checksum: the RFC 1950 wrapper java.util.zip writes.
        assertEquals(0x78, compressed[0].toInt() and 0xFF)
        assertEquals(0, ((compressed[0].toInt() and 0xFF) * 256 + (compressed[1].toInt() and 0xFF)) % 31)
        assertContentEquals(payload, inflate(compressed))
    }

    companion object {
        const val PASSWORD = "golden-archive-password"

        /** Compressible text plus incompressible bytes, so both directions take several 8 KiB rounds. */
        fun payload(): ByteArray = "Money Manager archive golden payload\n".repeat(800).encodeToByteArray() + Random(925).nextBytes(9_000)

        private const val JVM_ARCHIVE =
            """
            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
            sKzu6hfJyto/lYYj3HpjjIHg/AvtPCZhLvyNPg==
            """

        private const val NATIVE_ARCHIVE =
            """
            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
            DZwG/jfEYpLTa4DeY0UOHi3b0rnG6Xge1JQ2Pg==
            """
    }
}

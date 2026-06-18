package com.moneymanager.archive

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArchiveCodecTest {
    private val password = "correct horse battery staple"

    @Test
    fun roundTripRestoresOriginalBytes() =
        runTest {
            val original = "The quick brown fox jumps over the lazy dog".repeat(50).encodeToByteArray()
            val packed = ArchiveCodec.pack(original, password)
            val restored = ArchiveCodec.unpack(packed, password)
            assertContentEquals(original, restored)
        }

    @Test
    fun roundTripHandlesEmptyPayload() =
        runTest {
            val packed = ArchiveCodec.pack(ByteArray(0), password)
            assertContentEquals(ByteArray(0), ArchiveCodec.unpack(packed, password))
        }

    @Test
    fun compressibleDataShrinks() =
        runTest {
            val original = ByteArray(64 * 1024) // all zeros: highly compressible
            val packed = ArchiveCodec.pack(original, password)
            assertTrue(packed.size < original.size, "expected compression, got ${packed.size} >= ${original.size}")
        }

    @Test
    fun wrongPasswordFails() =
        runTest {
            val packed = ArchiveCodec.pack("secret data".encodeToByteArray(), password)
            assertFailsWith<ArchiveDecryptionException> {
                ArchiveCodec.unpack(packed, "wrong password")
            }
        }

    @Test
    fun tamperedCiphertextFails() =
        runTest {
            val packed = ArchiveCodec.pack("secret data".encodeToByteArray(), password)
            packed[packed.size - 1] = (packed[packed.size - 1].toInt() xor 0x01).toByte()
            assertFailsWith<ArchiveDecryptionException> {
                ArchiveCodec.unpack(packed, password)
            }
        }

    @Test
    fun foreignBlobFails() =
        runTest {
            assertFailsWith<ArchiveDecryptionException> {
                ArchiveCodec.unpack("not an archive".encodeToByteArray(), password)
            }
        }
}

package com.moneymanager.archive

import dev.whyoleg.cryptography.BinarySize.Companion.bytes
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.PBKDF2
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.operations.IvAuthenticatedCipher
import dev.whyoleg.cryptography.random.CryptographyRandom

/** Thrown when an archive can't be decrypted (wrong password) or is tampered with / corrupted. */
class ArchiveDecryptionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Compresses then encrypts a byte payload (and the inverse). Pure commonMain so every remote-storage
 * backend reuses the same shrink/encrypt pipeline (issue #86, point 7).
 *
 * Layout: `MAGIC | VERSION | salt[16] | AES-GCM(deflate(plain))`. The AES-GCM ciphertext produced by
 * cryptography-kotlin already embeds the random 12-byte IV and the authentication tag, so a wrong
 * password or tampering surfaces as an authentication failure during [unpack].
 */
object ArchiveCodec {
    private val MAGIC = byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 'A'.code.toByte(), 'R'.code.toByte())
    private const val VERSION: Byte = 1
    private const val SALT_LENGTH = 16
    private const val KEY_LENGTH = 32
    private const val PBKDF2_ITERATIONS = 210_000
    private val headerLength = MAGIC.size + 1 + SALT_LENGTH

    suspend fun pack(
        plain: ByteArray,
        password: String,
    ): ByteArray {
        require(password.isNotEmpty()) { "Password must not be empty" }
        val compressed = deflate(plain)
        val salt = CryptographyRandom.Default.nextBytes(SALT_LENGTH)
        val ciphertext = cipherFor(password, salt).encrypt(compressed)
        return MAGIC + byteArrayOf(VERSION) + salt + ciphertext
    }

    suspend fun unpack(
        packed: ByteArray,
        password: String,
    ): ByteArray {
        require(password.isNotEmpty()) { "Password must not be empty" }
        val (salt, ciphertext) = splitArchive(packed)
        val compressed =
            try {
                cipherFor(password, salt).decrypt(ciphertext)
            } catch (expected: Exception) {
                throw ArchiveDecryptionException("Wrong password or corrupted archive", expected)
            }
        return inflate(compressed)
    }

    /** Validates the header and returns the (salt, ciphertext) sections, or throws if malformed. */
    private fun splitArchive(packed: ByteArray): Pair<ByteArray, ByteArray> {
        if (packed.size < headerLength || !packed.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw ArchiveDecryptionException("Not a Money Manager archive")
        }
        val version = packed[MAGIC.size]
        if (version != VERSION) {
            throw ArchiveDecryptionException("Unsupported archive version: $version")
        }
        return packed.copyOfRange(MAGIC.size + 1, headerLength) to packed.copyOfRange(headerLength, packed.size)
    }

    private suspend fun cipherFor(
        password: String,
        salt: ByteArray,
    ): IvAuthenticatedCipher {
        val provider = CryptographyProvider.Default
        val derivation =
            provider.get(PBKDF2).secretDerivation(
                digest = SHA256,
                iterations = PBKDF2_ITERATIONS,
                outputSize = KEY_LENGTH.bytes,
                salt = salt,
            )
        val keyBytes = derivation.deriveSecretToByteArray(password.encodeToByteArray())
        val key = provider.get(AES.GCM).keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, keyBytes)
        return key.cipher()
    }
}

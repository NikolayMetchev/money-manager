package com.moneymanager.rest

import dev.whyoleg.cryptography.BinarySize.Companion.bits
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.io.encoding.Base64

private const val RSA_KEY_SIZE = 2048

/** Generates a fresh RSA-2048 key pair, PEM-encoded (PKCS#8 private, X.509 public). */
suspend fun generateScaKeyPair(): ScaKeyPair {
    val pair =
        CryptographyProvider.Default
            .get(RSA.PKCS1)
            .keyPairGenerator(RSA_KEY_SIZE.bits, SHA256)
            .generateKey()
    return ScaKeyPair(
        privateKeyPem = pair.privateKey.encodeToByteArray(RSA.PrivateKey.Format.PEM.Generic).decodeToString(),
        publicKeyPem = pair.publicKey.encodeToByteArray(RSA.PublicKey.Format.PEM.Generic).decodeToString(),
    )
}

/**
 * Signs [oneTimeToken] with the PKCS#8 PEM [privateKeyPem] using SHA-256 with RSA (PKCS#1 v1.5) and
 * returns the Base64-encoded signature — the value an SCA-protected API expects in its signature header.
 */
suspend fun signScaChallenge(
    privateKeyPem: String,
    oneTimeToken: String,
): String {
    val privateKey =
        CryptographyProvider.Default
            .get(RSA.PKCS1)
            .privateKeyDecoder(SHA256)
            .decodeFromByteArray(RSA.PrivateKey.Format.PEM.Generic, privateKeyPem.encodeToByteArray())
    return Base64.encode(privateKey.signatureGenerator().generateSignature(oneTimeToken.encodeToByteArray()))
}

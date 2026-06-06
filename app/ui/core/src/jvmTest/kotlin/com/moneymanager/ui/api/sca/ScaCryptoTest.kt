@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.moneymanager.ui.api.sca

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ScaCryptoTest {
    @Test
    fun `a generated key signs a token verifiably with its public key`() {
        val keyPair = generateScaKeyPair()
        val token = "one-time-token-abc123"

        val signatureBase64 = signScaChallenge(keyPair.privateKeyPem, token)

        val publicKeyDer = Base64.decode(pemBody(keyPair.publicKeyPem))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicKeyDer))
        val verifier =
            Signature.getInstance("SHA256withRSA").apply {
                initVerify(publicKey)
                update(token.encodeToByteArray())
            }
        assertTrue(verifier.verify(Base64.decode(signatureBase64)), "signature must verify with the public key")
    }

    @Test
    fun `the wrong token does not verify`() {
        val keyPair = generateScaKeyPair()
        val signatureBase64 = signScaChallenge(keyPair.privateKeyPem, "token-one")

        val publicKey =
            KeyFactory
                .getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(Base64.decode(pemBody(keyPair.publicKeyPem))))
        val verifier =
            Signature.getInstance("SHA256withRSA").apply {
                initVerify(publicKey)
                update("token-two".encodeToByteArray())
            }
        assertTrue(!verifier.verify(Base64.decode(signatureBase64)), "a different token must not verify")
    }

    @Test
    fun `each generated key pair is distinct`() {
        assertNotEquals(generateScaKeyPair().publicKeyPem, generateScaKeyPair().publicKeyPem)
    }

    private fun pemBody(pem: String): String =
        pem
            .lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
            .trim()
}

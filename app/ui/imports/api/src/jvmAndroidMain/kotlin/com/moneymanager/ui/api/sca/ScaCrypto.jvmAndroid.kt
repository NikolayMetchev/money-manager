package com.moneymanager.ui.api.sca

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import kotlin.io.encoding.Base64

private const val RSA_KEY_SIZE = 2048

actual fun generateScaKeyPair(): ScaKeyPair {
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(RSA_KEY_SIZE)
    val pair = generator.generateKeyPair()
    return ScaKeyPair(
        privateKeyPem = pemEncode("PRIVATE KEY", pair.private.encoded),
        publicKeyPem = pemEncode("PUBLIC KEY", pair.public.encoded),
    )
}

actual fun signScaChallenge(
    privateKeyPem: String,
    oneTimeToken: String,
): String {
    val der = pemDecode(privateKeyPem)
    val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
    val signature =
        Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(oneTimeToken.encodeToByteArray())
        }
    return Base64.encode(signature.sign())
}

private fun pemEncode(
    type: String,
    der: ByteArray,
): String {
    val body = Base64.encode(der).chunked(64).joinToString("\n")
    return "-----BEGIN $type-----\n$body\n-----END $type-----\n"
}

private fun pemDecode(pem: String): ByteArray {
    val body =
        pem
            .lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
            .trim()
    return Base64.decode(body)
}

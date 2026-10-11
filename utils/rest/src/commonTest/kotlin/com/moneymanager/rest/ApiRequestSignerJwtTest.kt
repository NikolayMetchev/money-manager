package com.moneymanager.rest

import com.moneymanager.domain.model.apistrategy.ApiRequestSigningConfig
import com.moneymanager.domain.model.apistrategy.JwtAlgorithm
import com.moneymanager.domain.model.apistrategy.JwtField
import com.moneymanager.domain.model.apistrategy.JwtSigningConfig
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.EdDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The JWT branch of [ApiRequestSigner], configured as Coinbase CDP keys need it (ES256, `kid`/`nonce`
 * header, `iss`/`sub`/`nbf`/`exp`/`uri` claims). ES256 signatures are randomised, so instead of a fixed
 * vector the token's signature is verified against the key's public half.
 */
class ApiRequestSignerJwtTest {
    private val config =
        ApiRequestSigningConfig(
            jwt =
                JwtSigningConfig(
                    algorithm = JwtAlgorithm.DETECT,
                    header =
                        listOf(
                            JwtField("alg", "{alg}"),
                            JwtField("kid", "{apiKey}"),
                            JwtField("nonce", "{nonceHex}"),
                            JwtField("typ", "JWT"),
                        ),
                    claims =
                        listOf(
                            JwtField("iss", "cdp"),
                            JwtField("sub", "{apiKey}"),
                            JwtField("nbf", "{now}", numeric = true),
                            JwtField("exp", "{exp}", numeric = true),
                            JwtField("uri", "{method} {host}{path}"),
                        ),
                ),
        )

    private suspend fun sign(
        secret: String,
        signingConfig: ApiRequestSigningConfig = config,
    ): SignedRequest =
        ApiRequestSigner(signingConfig).sign(
            endpointUrl = "https://api.coinbase.com/v2/accounts/abc/transactions",
            path = "/v2/accounts/abc/transactions",
            methodName = "v2/accounts/abc/transactions",
            params = linkedMapOf("limit" to "100", "starting_after" to "tx-1"),
            apiKey = API_KEY,
            apiSecret = secret,
            nonce = 0,
            requestId = 0,
            nowEpochSeconds = NOW,
        )

    @Test
    fun `renders the header and claims and signs them with the ES256 key`() =
        runTest {
            val signed = sign(PRIVATE_KEY)

            assertEquals("https://api.coinbase.com/v2/accounts/abc/transactions?limit=100&starting_after=tx-1", signed.url)
            assertNull(signed.body)
            val authorization = signed.headers.getValue("Authorization")
            assertTrue(authorization.startsWith("Bearer "), authorization)
            val (header, claims, signature) = authorization.removePrefix("Bearer ").split(".")

            val headerJson = decode(header)
            assertTrue(headerJson.startsWith("""{"alg":"ES256","kid":"$API_KEY","nonce":""""), headerJson)
            val nonce = headerJson.substringAfter(""""nonce":"""").substringBefore('"')
            assertEquals(32, nonce.length)
            assertTrue(nonce.all { it in '0'..'9' || it in 'a'..'f' }, nonce)
            assertEquals(
                """{"iss":"cdp","sub":"$API_KEY","nbf":$NOW,"exp":${NOW + 120},"uri":"GET api.coinbase.com/v2/accounts/abc/transactions"}""",
                decode(claims),
            )

            assertTrue(verifiesEs256(header, claims, signature), "signature does not verify against the public key")
        }

    @Test
    fun `an ES256 token signed on the JVM verifies here`() =
        runTest {
            val (header, claims, signature) = JVM_ES256_TOKEN.split(".")
            assertTrue(verifiesEs256(header, claims, signature), "the JVM's signature does not verify on this platform")
        }

    // ES256 signatures are randomised, but Ed25519 ones are not: the same key and message must give the
    // same bytes on every platform and provider.
    @Test
    fun `Ed25519 signs a fixed message to the JVM's bytes`() =
        runTest {
            assertEquals(JVM_ED25519_SIGNATURE, ed25519SignFixedMessage().toHexString())
        }

    private suspend fun verifiesEs256(
        header: String,
        claims: String,
        signature: String,
    ): Boolean =
        checkNotNull(CryptographyProvider.Default.getOrNull(ECDSA))
            .publicKeyDecoder(EC.Curve.P256)
            .decodeFromByteArray(EC.PublicKey.Format.PEM, PUBLIC_KEY.encodeToByteArray())
            .signatureVerifier(SHA256, ECDSA.SignatureFormat.RAW)
            .tryVerifySignature("$header.$claims".encodeToByteArray(), URL_SAFE.decode(signature))

    @Test
    fun `accepts a PEM key pasted with literal newline escapes or collapsed onto one line`() =
        runTest {
            val escaped = PRIVATE_KEY.trim().replace("\n", "\\n")
            val collapsed = PRIVATE_KEY.trim().replace("\n", " ")

            listOf(escaped, collapsed).forEach { secret ->
                assertEquals(PRIVATE_KEY, normalizePem(secret))
                assertTrue(sign(secret).headers.getValue("Authorization").startsWith("Bearer "))
            }
        }

    @Test
    fun `accepts the bare base64 DER of the key in SEC1 or PKCS8 form without PEM labels`() =
        runTest {
            val sec1Body = PRIVATE_KEY.lines().filterNot { it.startsWith("-----") || it.isBlank() }.joinToString("")
            val pkcs8Body = PKCS8_KEY.lines().filterNot { it.startsWith("-----") || it.isBlank() }.joinToString("")

            listOf(sec1Body, pkcs8Body, PKCS8_KEY, "{\"name\":\"$API_KEY\",\"privateKey\":\"${PRIVATE_KEY.trim().replace("\n", "\\n")}\"}")
                .forEach { secret -> assertTrue(sign(secret).headers.getValue("Authorization").startsWith("Bearer "), secret.take(12)) }
        }

    @Test
    fun `an Ed25519 secret signs as EdDSA when the algorithm is detected from the key`() =
        runTest {
            val authorization = sign(ED25519_SECRET).headers.getValue("Authorization")
            val (header, claims, signature) = authorization.removePrefix("Bearer ").split(".")

            assertTrue(decode(header).startsWith("""{"alg":"EdDSA","kid":"$API_KEY","""), decode(header))
            val decoder = checkNotNull(CryptographyProvider.Default.getOrNull(EdDSA)).publicKeyDecoder(EdDSA.Curve.Ed25519)
            val verified =
                decoder
                    .decodeFromByteArray(EdDSA.PublicKey.Format.PEM, ED25519_PUBLIC_KEY.encodeToByteArray())
                    .signatureVerifier()
                    .tryVerifySignature("$header.$claims".encodeToByteArray(), URL_SAFE.decode(signature))
            assertTrue(verified, "signature does not verify against the Ed25519 public key")
        }

    @Test
    fun `an Ed25519 secret given to an ES256-only recipe is rejected with a message saying so`() =
        runTest {
            val es256Only = config.copy(jwt = config.jwt!!.copy(algorithm = JwtAlgorithm.ES256))
            val error = assertFailsWith<IllegalArgumentException> { sign(ED25519_SECRET, es256Only) }
            assertTrue(error.message.orEmpty().contains("ECDSA"), error.message)
        }

    @Test
    fun `a numeric field with a leading plus sign renders as a valid JSON number`() =
        runTest {
            val plusClaim = config.copy(jwt = config.jwt!!.copy(claims = listOf(JwtField("n", "+5", numeric = true))))
            val claims = sign(PRIVATE_KEY, plusClaim).headers.getValue("Authorization").split(".")[1]

            assertEquals("""{"n":5}""", decode(claims))
        }

    private fun decode(segment: String): String = URL_SAFE.decode(segment).decodeToString()

    private companion object {
        const val API_KEY = "organizations/org-1/apiKeys/key-1"
        const val NOW = 1_700_000_000L
        val URL_SAFE = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

        // A throwaway P-256 key generated with `openssl ecparam -name prime256v1 -genkey -noout`.
        val PRIVATE_KEY =
            """
            -----BEGIN EC PRIVATE KEY-----
            MHcCAQEEILwbfi+kL3sQAqaJsLC6NIfvmm3wUIXh0TJDgx38TUVOoAoGCCqGSM49
            AwEHoUQDQgAE8dbx6ad/1STzz/k4jjguAKzdUM32LSUkt1xyccKeF3l2w/qDID0u
            Xfe95zGewLuMD1RdTOXzQCVauBwc8zk/xg==
            -----END EC PRIVATE KEY-----

            """.trimIndent()

        // A throwaway Ed25519 key (`openssl genpkey -algorithm ed25519`), as base64(seed + public key) -
        // the form Coinbase CDP hands out.
        const val ED25519_SECRET = "uPvpIHImFauItUxvOq+emVi/EwsKdNL8yISl/9c12LJv9/6YFYwYdpoBlPtJeQD/esf3bC4FIcpgZNrObsjcWw=="
        val ED25519_PUBLIC_KEY =
            """
            -----BEGIN PUBLIC KEY-----
            MCowBQYDK2VwAyEAb/f+mBWMGHaaAZT7SXkA/3rH92wuBSHKYGTazm7I3Fs=
            -----END PUBLIC KEY-----
            """.trimIndent()

        // An ES256 token signed by the JVM (JDK provider) with PRIVATE_KEY.
        val JVM_ES256_TOKEN =
            """
            eyJhbGciOiJFUzI1NiIsImtpZCI6Im9yZ2FuaXphdGlvbnMvb3JnLTEvYXBpS2V5cy9rZXktMSIsIm5vbmNlIjoiODkwNzg5MDlj
            NzYyNTUwNjQxZjFjMTI4NDkwYjc0ZGIiLCJ0eXAiOiJKV1QifQ.eyJpc3MiOiJjZHAiLCJzdWIiOiJvcmdhbml6YXRpb25zL29yZ
            y0xL2FwaUtleXMva2V5LTEiLCJuYmYiOjE3MDAwMDAwMDAsImV4cCI6MTcwMDAwMDEyMCwidXJpIjoiR0VUIGFwaS5jb2luYmFzZ
            S5jb20vdjIvYWNjb3VudHMvYWJjL3RyYW5zYWN0aW9ucyJ9.0O70TLHYAu4UsFhyvJRjqk93GO7KfdSREzDmvLH9tKSfqzMSnkJ_
            69FmTGEA8kSwG980AfsSQMFbrNW4FdMOfg
            """.filterNot(Char::isWhitespace)

        const val FIXED_MESSAGE = "Money Manager signs the same bytes on every platform"

        // Ed25519 over FIXED_MESSAGE with ED25519_SECRET's seed, computed by the JVM (JDK provider).
        const val JVM_ED25519_SIGNATURE =
            "fbeb26b4e4213ac3649f1a8d9bc41ee7e6cb83a70f06cd1a1297b4ab417a0c4980d8f757742a76f61074e33c0cceec30412cf33ff4856238c6b003540aaf770d"

        private suspend fun ed25519SignFixedMessage(): ByteArray =
            checkNotNull(CryptographyProvider.Default.getOrNull(EdDSA))
                .privateKeyDecoder(EdDSA.Curve.Ed25519)
                .decodeFromByteArray(EdDSA.PrivateKey.Format.RAW, Base64.decode(ED25519_SECRET).copyOf(ED25519_SEED_SIZE))
                .signatureGenerator()
                .generateSignature(FIXED_MESSAGE.encodeToByteArray())

        private const val ED25519_SEED_SIZE = 32

        // The same key in PKCS#8 form (`openssl pkcs8 -topk8 -nocrypt`).
        val PKCS8_KEY =
            """
            -----BEGIN PRIVATE KEY-----
            MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgvBt+L6QvexACpomw
            sLo0h++abfBQheHRMkODHfxNRU6hRANCAATx1vHpp3/VJPPP+TiOOC4ArN1QzfYt
            JSS3XHJxwp4XeXbD+oMgPS5d973nMZ7Au4wPVF1M5fNAJVq4HBzzOT/G
            -----END PRIVATE KEY-----
            """.trimIndent()
        val PUBLIC_KEY =
            """
            -----BEGIN PUBLIC KEY-----
            MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE8dbx6ad/1STzz/k4jjguAKzdUM32
            LSUkt1xyccKeF3l2w/qDID0uXfe95zGewLuMD1RdTOXzQCVauBwc8zk/xg==
            -----END PUBLIC KEY-----
            """.trimIndent()
    }
}

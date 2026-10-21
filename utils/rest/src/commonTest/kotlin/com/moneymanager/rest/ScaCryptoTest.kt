package com.moneymanager.rest

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScaCryptoTest {
    @Test
    fun signsExactlyAsJdkSha256WithRsa() =
        runTest {
            // Captured from the JDK's SHA256withRSA (the signer this replaced); PKCS#1 v1.5 is deterministic,
            // so every platform must produce these exact bytes.
            assertEquals(GOLDEN_SIGNATURE, signScaChallenge(GOLDEN_PRIVATE_KEY, GOLDEN_TOKEN))
        }

    @Test
    fun generatedKeysArePkcs8AndSpkiPem() =
        runTest {
            val pair = generateScaKeyPair()

            assertTrue(pair.privateKeyPem.startsWith("-----BEGIN PRIVATE KEY-----"))
            assertTrue(pair.publicKeyPem.startsWith("-----BEGIN PUBLIC KEY-----"))
            assertFalse("PRIVATE KEY" in pair.toString())
        }

    @Test
    fun generatedKeySignaturesVerifyWithItsPublicKey() =
        runTest {
            val pair = generateScaKeyPair()
            val signature = signScaChallenge(pair.privateKeyPem, GOLDEN_TOKEN)

            val publicKey =
                CryptographyProvider.Default
                    .get(RSA.PKCS1)
                    .publicKeyDecoder(SHA256)
                    .decodeFromByteArray(RSA.PublicKey.Format.PEM.Generic, pair.publicKeyPem.encodeToByteArray())
            assertTrue(
                publicKey.signatureVerifier().tryVerifySignature(GOLDEN_TOKEN.encodeToByteArray(), Base64.decode(signature)),
            )
        }
}

private const val GOLDEN_TOKEN = "one-time-token-1234"

private val GOLDEN_PRIVATE_KEY =
    """
    -----BEGIN PRIVATE KEY-----
    MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCukyxWCE93lTFj
    2qc7DrZkz/D/JA2beLbs4HGDL4Lo4SsD+t35Iq87H4eVIdvFVVXnlaKbnrF1+vaW
    6XjLmMjoZpValrO11D3p51UwpsyrVhcchRqOWsZBxrkrUqign+U+cjApTaPYoUnJ
    c66mRFCi4lPyPV/DtQpt3PBXnzn6Yb062lKI1jSYfcXoPmZI5U9kOq5Ixn/kGbBC
    w3JphuU/GIeiF8/+PFT1Zwcjm/bd0etJc3fHfScy98uJfyQOiUxoWfdYGXfbmnuO
    LMwGd2E8xHLiNkQjspqrrFRSWJhzsieFZX7IieIdjkQuOSQ4qMvR0yUgwQ91OZco
    +JObVSHjAgMBAAECggEAAfF2cWC3Tbv16EgjS2d/BUFBS/3BPwVJd/qJmO269c+n
    1GwC84Z8z9oqfrD5SsmH4sOKI1RRYmBQRV7E7vM87UVTDUaXKoN5yvQ9wQRiS9fx
    +unKYWJGSa4mGoLZiKwu95J60cLg+4TUFu94NraU9TMyvwQK4FlQC+gFLtyBXzWZ
    jOUZMo2d3XSBq8+0ur2QgcpFms9iPWi5CHoKgb7HMYizHijtWb/JYi745AuU5vTS
    KxOf7T4aFjqBOktb5AukRFiwMkm4BcX7c64wxVQw1P18NKDgWs1MGc3v+PdpmK7X
    WOaGxmK0R/1YyMcHuqpWSU3fX7VBsj6pxqGrdF84+QKBgQD1YNaSBJl8UyKfUtst
    +IrqIN20n+HqC/ADCxNgE92qov5gCrakqk662Ladov6WYVkN32H6xAdNe+mWmESg
    YFiBxKZ5XgTqHFaLtnV3Ij0ftxLrLO5n7B0vj8uhIteqIdFAFWXCmvqnuTa0RHyg
    u3jTRIt1nSZr4fLC13Xmm9GqiwKBgQC2IboGeRKjMLJPtKm93Bf73HG3e0bTrhCb
    Eypf9s+tQLk++iY1yzHPdpOcNHlAAyYtov2o7P236Qe65AnYo0zucCF9/Dcvs67j
    2qYnQ7FWsJINuMLdjukl8OBAd3vSCW8xksjt3WFfso2vGg3AIWWt7kFOdHfuUmei
    NUewqw3JCQKBgQCGbNG/1rM7D9RU0jBu480+bFjo/fnmnTmUgTnRFbEHLRoMBXJ/
    jSlqZRTOESBFw/jkh68GkOOrSCn0rWBTN9Tk6pWEwcQReQrUqVV6uZAg0suTeN72
    obxvdOhKU2swsiwPXmmMDhkpT2cvpKFd8LZFjzSTb09AJN04bo8RkeVv9wKBgF3t
    SshnzArI5ExiuV8mkF/dMfAT7VThYcL3wVS4LiYFSQOHcgkv/0Ikvr9B+H3goeHL
    OtHRVo9v6AKj88f7f4M/5KXaWoOJQNpVOnbpXB1+9fOJo0UrnGVqsdvNQtfw8Z05
    O9If50W5CYSSBSFel9d5FcjmG6uWZ8epfo1HydYxAoGADV424LsNkgLuTrnRB8Ya
    ytYUTMpIOI9LWsIRwizkD65kBo56e40Rydk8aU9nTtl9FGK6QRoKkCV6QAB5qEKh
    AaHQ31dLBKcDP+0LlWH15k0G7ODgsrz52u2K0z35plTLMW+ON+VjobuwgW6EjMg0
    Dsx4HXBSwNkd/AcDYLS1v4U=
    -----END PRIVATE KEY-----
    """.trimIndent()

private const val GOLDEN_SIGNATURE =
    "dAtGNwEnIX+Ya3UwCFaug1M0Bfh9MAVq4F8ssnv7XTQ7Mpzq9iNs2izpsimEiMhd" +
        "WeyZJIJzdF5OMB8GfDiV3pPYAhbUy+88GpaIjKi9YuH++BCJF4/g4mHt4GIIzO6l" +
        "R0QSGvaLZvoo9QE5r1RfX6M3+PKZSROlBbfTURWPhY2m8vTKD8L/czJiXs0oDvHL" +
        "WavIEES/mQzAM9ltceo8wHYOPgFD2NEnGaZ4hFcricVrT53dexV/WGOGcdlgHWmB" +
        "rNp7+sNQ6esnf3a1h9TS28WmVm/wsqVlZwH+SFlZOhtbVZXTWZjNWv97xduBbyk3" +
        "B1/I8+o6qdn6V7DQZuXhFQ=="

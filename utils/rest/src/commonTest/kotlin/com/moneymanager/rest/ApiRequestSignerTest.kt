package com.moneymanager.rest

import com.moneymanager.domain.model.apistrategy.ApiRequestSigningConfig
import com.moneymanager.domain.model.apistrategy.BodyFormat
import com.moneymanager.domain.model.apistrategy.FieldPlacement
import com.moneymanager.domain.model.apistrategy.NonceFormat
import com.moneymanager.domain.model.apistrategy.NonceSpec
import com.moneymanager.domain.model.apistrategy.ParamStringFormat
import com.moneymanager.domain.model.apistrategy.RequestIdFormat
import com.moneymanager.domain.model.apistrategy.RequestIdSpec
import com.moneymanager.domain.model.apistrategy.SecretEncoding
import com.moneymanager.domain.model.apistrategy.SigFieldLocation
import com.moneymanager.domain.model.apistrategy.SigPart
import com.moneymanager.domain.model.apistrategy.SignatureEncoding
import com.moneymanager.domain.model.apistrategy.SigningAlgorithm
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the single, provider-agnostic [ApiRequestSigner] reproduces the published signing vectors of
 * three exchanges whose schemes differ on every axis (algorithm, secret/output encoding, message shape,
 * field placement, body format) — validating that Crypto.com/Binance/Kraken are config-only, no code.
 *
 * Binance and Kraken use each exchange's own published example signature. Crypto.com uses an
 * independently-computed HMAC (openssl) over its documented `method+id+apiKey+sortedConcat(params)+nonce`
 * payload.
 */
class ApiRequestSignerTest {
    @Test
    fun `binance query-string HMAC-SHA256 hex vector`() =
        runTest {
            val config =
                ApiRequestSigningConfig(
                    algorithm = SigningAlgorithm.HMAC_SHA256,
                    secretEncoding = SecretEncoding.UTF8,
                    signatureEncoding = SignatureEncoding.HEX,
                    message = listOf(SigPart.QueryString, SigPart.Body),
                    apiKey = FieldPlacement(SigFieldLocation.HEADER, "X-MBX-APIKEY"),
                    nonce = NonceSpec(NonceFormat.EPOCH_MS, FieldPlacement(SigFieldLocation.QUERY, "timestamp")),
                    signature = FieldPlacement(SigFieldLocation.QUERY, "signature"),
                    bodyFormat = BodyFormat.QUERY_ONLY,
                )
            val signed =
                ApiRequestSigner(config).sign(
                    endpointUrl = "https://api.binance.com/api/v3/order",
                    path = "/api/v3/order",
                    methodName = "",
                    params =
                        linkedMapOf(
                            "symbol" to "LTCBTC",
                            "side" to "BUY",
                            "type" to "LIMIT",
                            "timeInForce" to "GTC",
                            "quantity" to "1",
                            "price" to "0.1",
                            "recvWindow" to "5000",
                        ),
                    apiKey = "vmPUZE6mv9SD5VNHk4HlWFsOr6aKE2zvsw0MuIgwCIPy6utIco14y7Ju91duEh8A",
                    apiSecret = "NhqPtmdSJYdKjVHjA7PZj4Mge3R5YNiP1e3UZjInClVN65XAbvqqM6A7H5fATj0j",
                    nonce = 1499827319559,
                    requestId = 0,
                )
            assertTrue(
                signed.url.endsWith("&signature=c8db56825ae71d6d79447849e617115f4a920fa2acdcab2b053c4b2838bd6b71"),
                "unexpected signed url: ${signed.url}",
            )
            assertEquals(
                "vmPUZE6mv9SD5VNHk4HlWFsOr6aKE2zvsw0MuIgwCIPy6utIco14y7Ju91duEh8A",
                signed.headers["X-MBX-APIKEY"],
            )
        }

    @Test
    fun `crypto_com sorted-concat HMAC-SHA256 hex vector`() =
        runTest {
            val config =
                ApiRequestSigningConfig(
                    algorithm = SigningAlgorithm.HMAC_SHA256,
                    secretEncoding = SecretEncoding.UTF8,
                    signatureEncoding = SignatureEncoding.HEX,
                    message =
                        listOf(
                            SigPart.Method,
                            SigPart.RequestId,
                            SigPart.ApiKey,
                            SigPart.ParamString(ParamStringFormat.SORTED_CONCAT),
                            SigPart.Nonce,
                        ),
                    apiKey = FieldPlacement(SigFieldLocation.BODY_FIELD, "api_key"),
                    nonce = NonceSpec(NonceFormat.EPOCH_MS, FieldPlacement(SigFieldLocation.BODY_FIELD, "nonce")),
                    signature = FieldPlacement(SigFieldLocation.BODY_FIELD, "sig"),
                    requestId = RequestIdSpec(RequestIdFormat.INCREMENTING, FieldPlacement(SigFieldLocation.BODY_FIELD, "id")),
                    method = FieldPlacement(SigFieldLocation.BODY_FIELD, "method"),
                    bodyFormat = BodyFormat.JSON_ENVELOPE,
                    paramsEnvelopeKey = "params",
                )
            val signed =
                ApiRequestSigner(config).sign(
                    endpointUrl = "https://api.crypto.com/exchange/v1/private/get-trades",
                    path = "/exchange/v1/private/get-trades",
                    methodName = "private/get-trades",
                    params = linkedMapOf("instrument_name" to "BTC_USD"),
                    apiKey = "testkey",
                    apiSecret = "testsecret",
                    nonce = 1600000000000,
                    requestId = 1,
                )
            // sig placed in the JSON envelope body; the request URL carries no query string.
            assertEquals("https://api.crypto.com/exchange/v1/private/get-trades", signed.url)
            assertEquals("application/json", signed.contentType)
            val body = signed.body ?: error("expected a JSON body")
            assertTrue(
                body.contains("\"sig\":\"319e600c3e701862bbb8671c3102f4f42ef466b6ccd335faa44091fd6069d005\""),
                "unexpected body: $body",
            )
            // The envelope carries method, id, api_key, nonce and the nested params object.
            assertTrue(body.contains("\"method\":\"private/get-trades\""), body)
            assertTrue(body.contains("\"params\":{\"instrument_name\":\"BTC_USD\"}"), body)
        }

    @Test
    fun `kraken path plus nested-sha256 HMAC-SHA512 base64 vector`() =
        runTest {
            val config =
                ApiRequestSigningConfig(
                    algorithm = SigningAlgorithm.HMAC_SHA512,
                    secretEncoding = SecretEncoding.BASE64,
                    signatureEncoding = SignatureEncoding.BASE64,
                    message = listOf(SigPart.Path, SigPart.Sha256(listOf(SigPart.Nonce, SigPart.Body))),
                    apiKey = FieldPlacement(SigFieldLocation.HEADER, "API-Key"),
                    nonce = NonceSpec(NonceFormat.EPOCH_MS, FieldPlacement(SigFieldLocation.BODY_FIELD, "nonce")),
                    signature = FieldPlacement(SigFieldLocation.HEADER, "API-Sign"),
                    bodyFormat = BodyFormat.FORM_URLENCODED,
                )
            val signed =
                ApiRequestSigner(config).sign(
                    endpointUrl = "https://api.kraken.com/0/private/AddOrder",
                    path = "/0/private/AddOrder",
                    methodName = "",
                    params =
                        linkedMapOf(
                            "ordertype" to "limit",
                            "pair" to "XBTUSD",
                            "price" to "37500",
                            "type" to "buy",
                            "volume" to "1.25",
                        ),
                    apiKey = "key",
                    apiSecret = "kQH5HW/8p1uGOVjbgWA7FunAmGO8lsSUXNsu3eow76sz84Q18fWxnyRzBHCd3pd5nE9qa99HAZtuZuj6F1huXg==",
                    nonce = 1616492376594,
                    requestId = 0,
                )
            assertEquals(
                "4/dpxb3iT4tp/ZCVEwSnEsLxx0bqyhLpdfOpc6fn7OR8+UClSV5n9E6aSS8MPtnRfp32bAb0nmbRn6H8ndwLUQ==",
                signed.headers["API-Sign"],
            )
            assertEquals("key", signed.headers["API-Key"])
            assertEquals(
                "nonce=1616492376594&ordertype=limit&pair=XBTUSD&price=37500&type=buy&volume=1.25",
                signed.body,
            )
        }
}

package com.moneymanager.domain.model.apistrategy

import kotlinx.serialization.Serializable

/** Asymmetric algorithm a [JwtSigningConfig] signs with. */
@Serializable
enum class JwtAlgorithm {
    /** ECDSA over P-256 with SHA-256; the api secret is a P-256 private key (PEM, or its base64 DER). */
    ES256,

    /** Ed25519; the api secret is a base64 32-byte seed or 64-byte seed + public key, or a PEM key. */
    EdDSA,

    /**
     * Whichever of the above the api secret is a key for — for a provider that issues both kinds (Coinbase
     * CDP keys are Ed25519 by default, ECDSA on request). Pair with an `alg` header of `{alg}`.
     */
    DETECT,
}

/**
 * One header or claim of a signed JWT. [template] may reference `{alg}` (the JWS name of the algorithm
 * the token is signed with), `{apiKey}`, `{nonceHex}` (a fresh random hex string), `{now}`/`{exp}` (epoch seconds, now and now + [JwtSigningConfig.ttlSeconds]),
 * `{method}` (the HTTP verb), `{host}` and `{path}` (the request URI path, without the query string).
 * When [numeric] is true the rendered value is emitted as a JSON number rather than a string.
 */
@Serializable
data class JwtField(
    val name: String,
    val template: String,
    val numeric: Boolean = false,
)

/**
 * Per-request JWT authentication: the engine renders [header] and [claims], signs
 * `base64url(header).base64url(claims)` with the api secret per [algorithm], and places the token
 * (prefixed by [prefix]) per [placement]. Coinbase CDP keys use ES256 with
 * `kid`/`nonce` header fields and `iss`/`sub`/`nbf`/`exp`/`uri` claims, sent as `Authorization: Bearer`.
 *
 * [header]/[claims] order is semantic (it's the serialised order) - keeps default insertion-order
 * serialization.
 */
@Serializable
data class JwtSigningConfig(
    val algorithm: JwtAlgorithm = JwtAlgorithm.ES256,
    val header: List<JwtField> = emptyList(),
    val claims: List<JwtField> = emptyList(),
    val ttlSeconds: Long = 120,
    val placement: FieldPlacement = FieldPlacement(SigFieldLocation.HEADER, "Authorization"),
    val prefix: String = "Bearer ",
)

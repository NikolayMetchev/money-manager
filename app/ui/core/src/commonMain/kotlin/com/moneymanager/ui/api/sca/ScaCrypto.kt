package com.moneymanager.ui.api.sca

/** Generates a fresh RSA-2048 key pair, PEM-encoded (PKCS#8 private, X.509 public). */
expect fun generateScaKeyPair(): ScaKeyPair

/**
 * Signs [oneTimeToken] with the PKCS#8 PEM [privateKeyPem] using SHA-256 with RSA and returns the
 * Base64-encoded signature — the value an SCA-protected API expects in its signature header.
 */
expect fun signScaChallenge(
    privateKeyPem: String,
    oneTimeToken: String,
): String

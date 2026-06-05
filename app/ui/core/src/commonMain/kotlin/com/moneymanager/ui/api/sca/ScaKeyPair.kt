package com.moneymanager.ui.api.sca

/** A PEM-encoded RSA key pair used for API request signing (e.g. Wise Strong Customer Authentication). */
data class ScaKeyPair(
    val privateKeyPem: String,
    val publicKeyPem: String,
)

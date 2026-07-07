package com.moneymanager.domain.model

/**
 * Entity supertype for anything that can denominate a [Money] amount: fiat [Currency], a crypto
 * asset ([CryptoAsset]), and future asset classes.
 *
 * Every asset exposes a [code], a human-readable [name], and a [scaleFactor] (the factor between a
 * stored integer minor-unit amount and its display value). [Money] embeds its asset so it can be
 * formatted and its precision determined without an extra lookup.
 */
sealed interface Asset {
    val id: AssetId
    val code: String
    val name: String

    /**
     * Stored amount = display amount × [scaleFactor]. 100 for 2-decimal fiat, 1 for 0-decimal
     * fiat (JPY), up to very large values for high-precision crypto (e.g. 1e18 for ETH).
     */
    val scaleFactor: Long
}

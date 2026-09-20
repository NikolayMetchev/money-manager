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
     * Stored amount = display amount × [scaleFactor]. Always a power of ten. Crypto assets use
     * [CryptoAsset.CRYPTO_SCALE_FACTOR] (1e18) and so, deliberately, do currencies — see
     * [CurrencyScaleFactors]. A narrower scale only ever comes from data the app did not create.
     */
    val scaleFactor: Long
}

/** Number of decimal places implied by [Asset.scaleFactor] (e.g. 100 → 2, 1e18 → 18). */
val Asset.decimalPlaces: Int
    get() {
        var factor = scaleFactor
        var places = 0
        while (factor > 1) {
            factor /= 10
            places++
        }
        return places
    }

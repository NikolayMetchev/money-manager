package com.moneymanager.domain.model

/**
 * Scale factor for newly-created currencies.
 *
 * Every currency uses the same scale as crypto assets ([CryptoAsset.CRYPTO_SCALE_FACTOR]) rather
 * than its ISO 4217 decimal-place count. An exchange-reported amount can legitimately carry more
 * precision than a currency's nominal decimal places (e.g. Kraken quotes a GBP trade's cost/fee at
 * the crypto pair's price precision, landing sub-penny), and that precision must not be rounded
 * away to fit a narrower scale. Display formatting is unaffected: it is locale/ISO-currency driven
 * (see `utils/currency`), independent of the stored scale.
 */
object CurrencyScaleFactors {
    /** The scale factor assigned to every currency (fiat or otherwise) — see the class doc for why. */
    const val DEFAULT_SCALE_FACTOR: Long = CryptoAsset.CRYPTO_SCALE_FACTOR
}

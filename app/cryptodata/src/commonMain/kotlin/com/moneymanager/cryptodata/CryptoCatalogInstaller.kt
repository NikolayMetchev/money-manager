package com.moneymanager.cryptodata

import com.moneymanager.domain.model.CryptoRegistry

/**
 * Installs the bundled offline catalog and layers any previously-refreshed catalog on top. Call once at
 * startup (from the platform entry point) before the first import. Cheap and idempotent — the bundled
 * dataset is parsed lazily on first lookup, not here.
 */
fun installCryptoCatalog() {
    CryptoRegistry.install(BundledCryptoCatalog)
    readStoredCryptoCatalogText()?.let { CryptoRegistry.installOverrides(parseCryptoDataset(it)) }
}

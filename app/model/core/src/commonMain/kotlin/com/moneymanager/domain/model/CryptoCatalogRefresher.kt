package com.moneymanager.domain.model

/**
 * Refreshes the crypto catalog from a network source, persisting the result so it survives restarts and
 * layering it over the bundled offline catalog via [CryptoRegistry.installOverrides]. Implemented in
 * `app/cryptodata`; the UI holds it as a nullable optional service (absent when offline/unconfigured).
 */
interface CryptoCatalogRefresher {
    /** Fetches + persists + installs the latest catalog and returns the number of entries loaded. */
    suspend fun refresh(): Int
}

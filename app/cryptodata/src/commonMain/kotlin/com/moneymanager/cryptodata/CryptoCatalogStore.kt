package com.moneymanager.cryptodata

// Persistence for the network-refreshed crypto catalog, stored as a plain TSV file in the app data
// directory (outside any money-manager database — it is external, non-user, non-sync reference data, so
// it must not go through the DB write seam). The bundled catalog is always available regardless; this
// only holds the optional refreshed layer.

/** Reads the persisted refreshed catalog text, or null if none has been written / it isn't readable. */
expect fun readStoredCryptoCatalogText(): String?

/** Writes the refreshed catalog [text] to the app data dir. Returns false if it couldn't be persisted. */
expect fun writeStoredCryptoCatalogText(text: String): Boolean

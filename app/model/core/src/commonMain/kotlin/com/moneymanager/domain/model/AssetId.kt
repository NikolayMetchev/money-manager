package com.moneymanager.domain.model

/**
 * Identity supertype shared by every asset class (fiat [CurrencyId], [CryptoId], and future
 * asset kinds such as stocks or commodities).
 *
 * All asset ids are drawn from a single `asset` id space in the database (mirroring how every
 * transaction id — [TransferId], trade ids — is drawn from `transaction_id`). This lets a
 * transfer, trade, or balance denominate in any asset uniformly via an [AssetId].
 */
interface AssetId {
    val id: Long
}

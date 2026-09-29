package com.moneymanager.domain.model.csvstrategy

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/**
 * Marks a strategy as a **reconciliation source** (e.g. a crypto-tax platform's export such as
 * Koinly's): its imports never touch real accounts. Every account it resolves or creates is a shadow
 * account tagged with [sourceName], so the imported data can be compared against the real
 * transactions (via shadow→real account links) without contributing to real balances or taking part in
 * dedupe/reconciliation of real imports.
 *
 * @property sourceName Groups all shadow accounts (and so all imports) of one source; several
 *                      strategies may share it, e.g. two export formats of the same platform.
 * @property linkableAccountPrefix Names the source's **wallet** accounts — the ones that mirror a real
 *                                 account and so need a link (auto-linked by name after the prefix, or
 *                                 flagged for the user to fix). Shadow accounts without it (counterparties
 *                                 such as "Koinly: Reward", the fee account) are never linked. Null treats
 *                                 every shadow account of the source as a wallet.
 */
@Serializable
data class ReconciliationConfig(
    val sourceName: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val linkableAccountPrefix: String? = null,
) {
    init {
        require(sourceName.isNotBlank()) { "sourceName must not be blank" }
    }
}

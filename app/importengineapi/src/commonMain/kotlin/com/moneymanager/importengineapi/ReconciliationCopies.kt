package com.moneymanager.importengineapi

import com.moneymanager.domain.model.AccountId

/** Where a copied transaction's end lands among the real accounts (#1475). */
sealed interface CopyEnd {
    /** The real account a linked wallet stands for. */
    data class Real(
        val account: AccountId,
    ) : CopyEnd

    /** The fee account of [account], the real account whose wallet's fee account the source paid into. */
    data class FeeAccountOf(
        val account: AccountId,
    ) : CopyEnd
}

/**
 * The one mapping "Copy to Money Manager" books through, both when a person copies a source row and whenever the
 * source's re-import brings the copy up to date: a wallet becomes the one real account it is linked to, and a wallet's
 * fee account becomes that real account's fee account.
 *
 * @param realOf each shadow account's linked real accounts (`reconciliation_account_link`).
 * @param walletOfFeeAccount each shadow fee account's wallet (its `fee-account-for` attribute).
 */
class CopyEnds(
    private val realOf: Map<AccountId, Collection<AccountId>>,
    private val walletOfFeeAccount: Map<AccountId, AccountId>,
) {
    /** Where [shadow] lands, or null when it, or the wallet whose fee account it is, isn't linked to exactly one account. */
    fun end(shadow: AccountId): CopyEnd? {
        walletOfFeeAccount[shadow]?.let { wallet -> return (end(wallet) as? CopyEnd.Real)?.let { CopyEnd.FeeAccountOf(it.account) } }
        return realOf[shadow]?.distinct()?.singleOrNull()?.let(CopyEnd::Real)
    }

    /** The account whose links decide [shadow]'s end: its wallet when it is a fee account, else itself. */
    fun linkedAccountOf(shadow: AccountId): AccountId = walletOfFeeAccount[shadow]?.let(::linkedAccountOf) ?: shadow

    /** How many real accounts [shadow] is linked to. */
    fun linkCount(shadow: AccountId): Int = realOf[shadow]?.distinct()?.size ?: 0
}

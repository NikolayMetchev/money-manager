package com.moneymanager.ui.screens.reconciliation

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.reconciliation.ReconciliationLink
import com.moneymanager.domain.model.reconciliation.ReconciliationSource
import com.moneymanager.domain.model.reconciliation.ShadowAccount
import com.moneymanager.reconciliation.ReconciliationResult
import com.moneymanager.reconciliation.UnlinkedWallet

/**
 * Reconciliation results and account-link plans kept above the screen, so coming back from a followed link shows them again instead of
 * reading and matching every leg a second time. A result stays good until the database is written to
 * (`ImportEngine.writeVersion`), its inputs change, or a person asks for a refresh.
 */
class ReconciliationCache {
    private class Entry(
        val shadowIds: Set<AccountId>,
        val links: List<ReconciliationLink>,
        val writeVersion: Long,
        val result: ReconciliationResult,
    )

    private val entries = mutableMapOf<String, Entry>()

    // Transaction writes invalidate comparisons. Account-link plans depend only on accounts and links.
    private val walletPlans = mutableMapOf<String, Pair<WalletLinkInputs, WalletPlans>>()

    internal fun getWalletPlans(inputs: WalletLinkInputs): WalletPlans? =
        walletPlans[inputs.source.name]?.takeIf { it.first == inputs }?.second

    internal fun putWalletPlans(
        inputs: WalletLinkInputs,
        plans: WalletPlans,
    ) {
        walletPlans[inputs.source.name] = inputs to plans
    }

    fun get(
        sourceName: String,
        shadowIds: Set<AccountId>,
        links: List<ReconciliationLink>,
        writeVersion: Long,
    ): ReconciliationResult? =
        entries[sourceName]?.takeIf { it.shadowIds == shadowIds && it.links == links && it.writeVersion == writeVersion }?.result

    fun put(
        sourceName: String,
        shadowIds: Set<AccountId>,
        links: List<ReconciliationLink>,
        writeVersion: Long,
        result: ReconciliationResult,
    ) {
        entries[sourceName] = Entry(shadowIds, links, writeVersion, result)
    }

    /**
     * The last result stored for [sourceName] whatever its inputs, to show while they load, provided nothing has been
     * written since: a write may have changed what those inputs are (a deleted wallet), which loaded-empty inputs
     * would otherwise be mistaken for.
     */
    fun peek(
        sourceName: String,
        writeVersion: Long,
    ): ReconciliationResult? = entries[sourceName]?.takeIf { it.writeVersion == writeVersion }?.result

    fun invalidate(sourceName: String) {
        entries.remove(sourceName)
    }

    internal fun invalidateWalletPlans(sourceName: String) {
        walletPlans.remove(sourceName)
    }
}

internal data class WalletLinkInputs(
    val source: ReconciliationSource,
    val shadowAccounts: List<ShadowAccount>,
    val links: List<ReconciliationLink>,
    val realAccounts: List<Account>,
    val accounts: List<Account>,
)

internal data class WalletPlans(
    val unlinked: List<UnlinkedWallet>,
    val creation: Map<AccountId, String>,
    val suggested: Map<AccountId, AccountId>,
)

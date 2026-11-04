package com.moneymanager.ui.screens.reconciliation

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.reconciliation.ReconciliationLink
import com.moneymanager.reconciliation.ReconciliationResult

/**
 * Reconciliation results kept above the screen, so coming back from a followed link shows them again instead of
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
}

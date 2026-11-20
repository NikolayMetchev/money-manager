package com.moneymanager.importer

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.observation.Observation
import com.moneymanager.domain.model.observation.ObservationContent
import com.moneymanager.domain.model.observation.ObservationOwner
import com.moneymanager.domain.model.observation.ObservedValues
import com.moneymanager.domain.repository.write.AccountAttributeWriteRepository
import com.moneymanager.domain.repository.write.ObservationWriteRepository
import com.moneymanager.domain.repository.write.ReconciliationLinkWriteRepository
import com.moneymanager.importengineapi.CopyEnd
import com.moneymanager.importengineapi.CopyEnds
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.LocalTransactionKey
import com.moneymanager.importengineapi.MaterializationReport
import com.moneymanager.importengineapi.MaterializationRequest
import com.moneymanager.importengineapi.ObservationMutation
import com.moneymanager.importengineapi.ObservationSlot
import com.moneymanager.importengineapi.ObservationTransaction
import com.moneymanager.importengineapi.ReservedTransactions
import kotlinx.coroutines.flow.first

/**
 * Books a reconciliation source's rows onto the real accounts (#1475, [ImportBatch.reconciliationCopies]), and books such
 * copies again once a re-run has vacated them.
 */
internal class ReconciliationCopier(
    private val observationRepository: ObservationWriteRepository,
    private val reconciliationLinkRepository: ReconciliationLinkWriteRepository,
    private val accountAttributeRepository: AccountAttributeWriteRepository,
    private val materialize: suspend (List<ObservationMutation>, MaterializationRequest) -> MaterializationReport,
) {
    class Recopied(
        val ids: Set<TransactionId>,
        val report: MaterializationReport,
    )

    /**
     * Books again each copy (#1475) a re-run vacated, from what its record says now: the record said its slot again, on a
     * transaction the source still counts, and the copy's wallets are still linked to one account each. The copy keeps its
     * id, so the match and a person's edits stay on it, and a conflict compares with what it merged to before. A copy whose
     * row is gone, excluded or no longer maps stays reserved, and the settle removes it like any record not said again.
     */
    suspend fun recopy(reserved: ReservedTransactions): Recopied {
        val observations = observationRepository
        val copies = observations.getReserved().filter { it.isCopy() && it.transactionId in reserved.ids }
        if (copies.isEmpty()) return Recopied(emptySet(), MaterializationReport())
        val ends = loadCopyAccounts()
        val ids = mutableSetOf<TransactionId>()
        for (copy in copies) {
            val content = recopiedContent(copy, observations, ends) ?: continue
            if (content != copy.content) observations.updateContent(copy.id, content)
            observations.unreserve(copy.id)
            ids += copy.transactionId
        }
        if (ids.isEmpty()) return Recopied(emptySet(), MaterializationReport())
        val report =
            materialize(
                emptyList(),
                MaterializationRequest(Source.Reconciliation, ids, priorMerges = reserved.priorMerges.filterKeys { it in ids }),
            )
        return Recopied(ids, report)
    }

    /** What [copy] books now: what its record says again at the copied slot, mapped onto the real accounts, or null when it says nothing there any more. */
    private suspend fun recopiedContent(
        copy: Observation,
        observations: ObservationWriteRepository,
        ends: CopyAccounts,
    ): ObservationContent? {
        val base = copy.slot.removePrefix(ObservationSlot.COPY_PREFIX)
        val said =
            observations.getObservationsOf(copy.owner).firstOrNull {
                it.slot == base && (copy.owner is ObservationOwner.Group || !observations.isReserved(it.id))
            } ?: return null
        return ends.contentOf(said.transactionId)?.takeIf { it.values.kind == copy.content.values.kind }
    }

    /**
     * Copies each of [shadowTransactions] onto the real accounts (#1475, [ImportBatch.reconciliationCopies]): the record that
     * says the shadow transaction says its copy too, in the [ObservationSlot.copy] of its slot, and the copy is materialized
     * as a [Source.Reconciliation] transaction.
     */
    suspend fun copyToRealAccounts(shadowTransactions: List<TransactionId>): List<TransactionId> {
        val observations = observationRepository
        val ends = loadCopyAccounts()
        val keys = shadowTransactions.indices.map { LocalTransactionKey("copy-$it") }
        val mutations =
            shadowTransactions.mapIndexed { index, shadow ->
                val said =
                    observations
                        .getObservationsOfTransaction(shadow)
                        .filter { it.owner !is ObservationOwner.Manual && !it.isCopy() }
                        .minWithOrNull(compareBy({ it.owner !is ObservationOwner.Record }, { it.id.id }))
                requireNotNull(said) { "No record of a reconciliation source says $shadow" }
                val slot = ObservationSlot.copy(said.slot)
                require(observations.getObservationsOf(said.owner).none { it.slot == slot }) { "$shadow is copied already" }
                val content = requireNotNull(ends.contentOf(shadow)) { "$shadow can't be copied onto the real accounts" }
                ObservationMutation.Upsert(said.owner, slot, ObservationTransaction.New(keys[index]), content)
            }
        val report = materialize(mutations, MaterializationRequest(Source.Reconciliation))
        return keys.map { requireNotNull(report.newTransactionIds[it]) { "A copy was not booked" } }
    }

    /** Where a copy's ends land ([CopyEnds]), with the real accounts' fee accounts, read once per settle or copy batch. */
    private inner class CopyAccounts(
        private val ends: CopyEnds,
        private val feeAccountOf: Map<AccountId, AccountId>,
    ) {
        private fun accountOf(shadow: AccountId): AccountId? =
            when (val end = ends.end(shadow)) {
                is CopyEnd.Real -> end.account
                is CopyEnd.FeeAccountOf -> feeAccountOf[end.account]
                null -> null
            }

        /** What a copy of [shadow] says: its values on the real accounts, or null when it is excluded or doesn't map. */
        suspend fun contentOf(shadow: TransactionId): ObservationContent? {
            val materialized = observationRepository.getMaterialized(shadow) ?: return null
            if (materialized.attributes.any { it.typeId.id == WellKnownIds.EXCLUDED_ATTR_TYPE_ID }) return null
            val values =
                when (val v = materialized.values) {
                    is ObservedValues.Transfer -> {
                        v.copy(
                            sourceAccountId = accountOf(v.sourceAccountId) ?: return null,
                            targetAccountId = accountOf(v.targetAccountId) ?: return null,
                        )
                    }

                    is ObservedValues.Trade -> {
                        v.copy(
                            fromAccountId = (ends.end(v.fromAccountId) as? CopyEnd.Real)?.account ?: return null,
                            toAccountId = (ends.end(v.toAccountId) as? CopyEnd.Real)?.account ?: return null,
                        )
                    }
                }
            return ObservationContent(values)
        }
    }

    private suspend fun loadCopyAccounts(): CopyAccounts {
        val links = reconciliationLinkRepository
        val ends =
            CopyEnds(
                realOf = links.getLinks().first().groupBy({ it.shadowAccountId }, { it.realAccountId }),
                walletOfFeeAccount =
                    links
                        .getShadowAccounts()
                        .first()
                        .mapNotNull { shadow -> shadow.feeAccountFor?.let { shadow.accountId to it } }
                        .toMap(),
            )
        val feeAccountOf =
            accountAttributeRepository
                .getByType(FEE_ACCOUNT_FOR_TYPE)
                .first()
                .mapNotNull { attr -> attr.value.toLongOrNull()?.let { AccountId(it) to attr.accountId } }
                .toMap()
        return CopyAccounts(ends, feeAccountOf)
    }
}

/** Whether this observation is a reconciliation copy (#1475): what its owner's record says on the real accounts. */
internal fun Observation.isCopy(): Boolean = slot.startsWith(ObservationSlot.COPY_PREFIX)

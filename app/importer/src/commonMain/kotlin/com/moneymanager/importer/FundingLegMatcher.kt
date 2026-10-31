package com.moneymanager.importer

import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import kotlin.time.Instant

/**
 * Picks the existing leg a reconcile candidate should link against: the unconsumed [candidates] transfer
 * whose timestamp is nearest [targetTimestamp] and [within] the reconcile window, or null when none qualifies.
 *
 * Callers pre-filter their own candidates first (e.g. by (fundingAccount, conduit, amount), or by
 * asset+tolerance+direction for an internal-transfer bridge), so this resolves only the one-to-one,
 * nearest-timestamp choice — the single rule the `ImportDeduper` reconcile paths (funding-card and
 * internal-transfer) must agree on. [consumed] holds the ids already claimed in this batch; the caller
 * adds the returned id to its own set (scope is one import — see `ImportDeduper.consumedReconcileIds`).
 */
internal fun selectNearestUnconsumedLeg(
    candidates: List<Pair<TransferId, Transfer>>,
    targetTimestamp: Instant,
    consumed: Set<TransferId>,
    within: (Transfer) -> Boolean,
): TransferId? =
    candidates
        .asSequence()
        .filter { (id, existing) -> id !in consumed && within(existing) }
        .minByOrNull { (_, existing) -> (targetTimestamp - existing.timestamp).absoluteValue }
        ?.first

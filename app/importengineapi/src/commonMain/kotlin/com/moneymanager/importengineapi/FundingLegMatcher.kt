@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.importengineapi

import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Picks the existing leg a reconcile candidate should link against: the unconsumed [candidates] transfer
 * whose timestamp is nearest [targetTimestamp] and within [window], or null when none qualifies.
 *
 * Callers pre-filter their own candidates first (e.g. by (fundingAccount, conduit, amount), or by
 * asset+tolerance+direction for an internal-transfer bridge), so this resolves only the one-to-one,
 * nearest-timestamp choice — the single rule the engine's `ImportDeduper` reconcile paths (funding-card
 * and internal-transfer) and the re-import planner (`CsvReimport.computeFundingReconcileReruns`) must
 * agree on. Sharing it here keeps those paths from drifting. [consumed] holds the ids already claimed in
 * this batch; the caller adds the returned id to its own set (scope is one import — see
 * `ImportDeduper.consumedReconcileIds`).
 */
fun selectNearestUnconsumedLeg(
    candidates: List<Pair<TransferId, Transfer>>,
    targetTimestamp: Instant,
    window: Duration,
    consumed: Set<TransferId>,
): TransferId? =
    candidates
        .asSequence()
        .filter { (id, existing) -> id !in consumed && (targetTimestamp - existing.timestamp).absoluteValue <= window }
        .minByOrNull { (_, existing) -> (targetTimestamp - existing.timestamp).absoluteValue }
        ?.first

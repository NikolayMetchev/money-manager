@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.importengineapi

import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Picks the funding leg a conduit-spend row should reconcile against: the unconsumed [candidates]
 * transfer whose timestamp is nearest [targetTimestamp] and within [window], or null when none qualifies.
 *
 * Callers bucket their own candidates by (fundingAccount, conduit, amount) first, so this resolves only
 * the one-to-one, nearest-timestamp choice — the single rule the engine's `ImportDeduper` funding
 * reconcile and the re-import planner (`CsvReimport.computeFundingReconcileReruns`) must agree on. Sharing
 * it here keeps those two paths from drifting. [consumed] holds the ids already claimed in this batch; the
 * caller adds the returned id to its own set (scope is one import — see `ImportDeduper.consumedFundingIds`).
 */
fun selectNearestUnconsumedFundingLeg(
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

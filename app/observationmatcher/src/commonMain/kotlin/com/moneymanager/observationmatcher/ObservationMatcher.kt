package com.moneymanager.observationmatcher

import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.observation.ObservationOwner
import com.moneymanager.domain.model.observation.ObservationPin
import com.moneymanager.domain.model.observation.ObservationSplit

/**
 * Assigns observations to transactions: which records are the same movement, decided over a whole scope at once.
 *
 * `ImportDeduper` decides row by row, in file order, each row claiming the best record still unclaimed, so a row can
 * take the record its later neighbour explains better, and importing the same files in another order books something
 * else. Here every candidate pair of the scope is found first, then the pairs are taken best first: by [rules] rank,
 * then distance, then the two observations' record keys. Nothing depends on the order the observations are given in,
 * on their ids, or on the order their files were imported in.
 *
 * Taking a pair joins the two observations' groups, so a movement three exports record is one transaction of three
 * observations. Two groups join only when every pair across them is one some rule pairs (a group never grows by
 * chaining near neighbours), never two rows of one container that only a rule [PairRule.joinsSameContainer] would
 * join, never across a person's split, and never two different pinned transactions.
 *
 * Then [stages] look at the transactions as a whole, for movements two sources record in different shapes, and link
 * one to be excluded from balances by the other ([MatchResult.exclusions]).
 *
 * Pure and DB-free: the caller loads the scope ([scopeOf]) and writes the [MatchResult].
 */
class ObservationMatcher(
    private val rules: List<PairRule>,
    private val stages: List<MatchStage> = emptyList(),
) {
    /**
     * Assigns every observation of [scope]. [pins] fix an observation to a transaction and [splits] keep two apart:
     * both are a person's, so the matcher takes them as given. A pin binds only the observation it names: to keep an
     * observation with the others of a transaction, pin them all. Pins and splits naming observations outside the
     * scope are ignored.
     */
    fun match(
        scope: Collection<MatchObservation>,
        pins: Collection<ObservationPin> = emptyList(),
        splits: Collection<ObservationSplit> = emptyList(),
    ): MatchResult {
        val observations = scope.sortedWith(ORDER)
        require(observations.map { it.ref }.toSet().size == observations.size) { "an observation appears twice in the scope" }
        val indexById = observations.withIndex().mapNotNull { (i, o) -> o.storedId?.let { it to i } }.toMap()
        val pinned = pins.mapNotNull { pin -> indexById[pin.observationId]?.let { it to pin.transactionId } }.toMap()
        val apart =
            splits
                .mapNotNull { split ->
                    val a = indexById[split.first] ?: return@mapNotNull null
                    val b = indexById[split.second] ?: return@mapNotNull null
                    setOf(a, b)
                }.toSet()

        val groups = Groups(observations.size)
        val joins = mutableListOf<Join>()
        // A person's pins come first: every observation pinned to one transaction is in its group.
        pinned.entries
            .groupBy({ it.value }, { it.key })
            .values
            .forEach { members ->
                val sorted = members.sorted()
                sorted.zipWithNext().forEach { (a, b) ->
                    groups.union(a, b)
                    joins += Join(observations[a].ref, observations[b].ref, Join.PIN)
                }
            }

        for (rankCandidates in candidatePairs(observations)
            .groupBy(Candidate::rank)
            .entries
            .sortedBy { it.key }
            .map { it.value }) {
            for (candidate in maximumMatchingFirst(rankCandidates, groups)) {
                val x = groups.find(candidate.a)
                val y = groups.find(candidate.b)
                if (x != y && canJoin(observations, groups.members(x), groups.members(y), pinned, apart) &&
                    rules[candidate.rank].joinsGroups(
                        groups.members(x).map { observations[it] },
                        groups.members(y).map { observations[it] },
                    )
                ) {
                    groups.union(x, y)
                    joins += Join(observations[candidate.a].ref, observations[candidate.b].ref, rules[candidate.rank].name)
                }
            }
        }

        val targets = targetsOf(observations, groups, pinned)
        val assignments = observations.indices.associate { i -> observations[i].ref to targets.getValue(groups.find(i)) }
        val changes =
            observations.mapNotNull { o ->
                val to = assignments.getValue(o.ref)
                if (o.current != null && to == TransactionTarget.Existing(o.current)) null else AssignmentChange(o.ref, o.current, to)
            }
        val kept =
            targets.values
                .filterIsInstance<TransactionTarget.Existing>()
                .map { it.id }
                .toSet()
        val released = observations.mapNotNullTo(mutableSetOf()) { it.current }.filterNotTo(mutableSetOf()) { it in kept }
        return MatchResult(assignments, changes, released, joins, exclusionsOf(observations, assignments))
    }

    /**
     * The scope a change needs: the [changed] observations (new content, or new altogether), every [stored] one that
     * shares a transaction with one in the scope (including what [removed] observations leave behind), and every stored
     * one some rule pairs with one in the scope, until nothing more joins. [changed] replaces a stored observation with
     * the same ref, and [removed] ones are left out.
     */
    fun scopeOf(
        changed: Collection<MatchObservation>,
        removed: Collection<MatchObservation>,
        stored: Collection<MatchObservation>,
    ): List<MatchObservation> {
        val gone = removed.mapTo(mutableSetOf()) { it.ref } + changed.map { it.ref }
        val pool = stored.filter { it.ref !in gone }
        val byTransaction = pool.filter { it.current != null }.groupBy { it.current }
        val byBucket = rules.map { rule -> pool.mapNotNull { o -> rule.bucket(o)?.let { it to o } }.groupBy({ it.first }, { it.second }) }
        val byStageKey =
            stages.map { stage -> pool.flatMap { o -> stage.scopeKeys(o).map { it to o } }.groupBy({ it.first }, { it.second }) }

        val inScope = linkedMapOf<ObservationRef, MatchObservation>()
        val queue = ArrayDeque<MatchObservation>()

        fun add(observation: MatchObservation) {
            if (inScope.put(observation.ref, observation) == null) queue += observation
        }
        changed.forEach(::add)
        removed.mapNotNull { it.current }.forEach { id -> byTransaction[id].orEmpty().forEach(::add) }
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            next.current?.let { id -> byTransaction[id].orEmpty().forEach(::add) }
            rules.forEachIndexed { rank, rule ->
                val bucket = rule.bucket(next) ?: return@forEachIndexed
                byBucket[rank][bucket].orEmpty().filter { rule.distance(next, it) != null }.forEach(::add)
            }
            stages.forEachIndexed { index, stage ->
                stage.scopeKeys(next).forEach { key -> byStageKey[index][key].orEmpty().forEach(::add) }
            }
        }
        return inScope.values.toList()
    }

    /**
     * Runs [stages] over the transactions of [assignments], each transaction's members in the matcher's order and the
     * transactions in the order of their first members.
     */
    private fun exclusionsOf(
        observations: List<MatchObservation>,
        assignments: Map<ObservationRef, TransactionTarget>,
    ): List<ExclusionLink> {
        if (stages.isEmpty()) return emptyList()
        val transactions =
            observations
                .groupBy { assignments.getValue(it.ref) }
                .map { (target, members) -> MatchedTransaction(target, members) }
        val claimed = mutableSetOf<TransactionTarget>()
        return stages.flatMap { stage ->
            stage.exclusions(transactions, claimed).map { match ->
                claimed += match.targets
                val excluded = match.excluded.flatMap { it.members }.mapTo(linkedSetOf()) { it.ref }
                ExclusionLink(stage.reason, match.by.flatMap { it.members }.mapTo(linkedSetOf()) { it.ref } + excluded, excluded)
            }
        }
    }

    private data class Candidate(
        val rank: Int,
        val distance: Long,
        val a: Int,
        val b: Int,
    )

    /** Every pair a rule finds, best first; [Candidate.a] is before [Candidate.b] in the matcher's order. */
    private fun candidatePairs(observations: List<MatchObservation>): List<Candidate> =
        buildList {
            rules.forEachIndexed { rank, rule ->
                val buckets = mutableMapOf<Any, MutableList<Int>>()
                for ((index, observation) in observations.withIndex()) {
                    val key = rule.bucket(observation) ?: continue
                    buckets.getOrPut(key) { mutableListOf() }.add(index)
                }

                fun addPair(
                    first: Int,
                    second: Int,
                ) {
                    val a = minOf(first, second)
                    val b = maxOf(first, second)
                    rule.distance(observations[a], observations[b])?.let { add(Candidate(rank, it, a, b)) }
                }
                for (bucket in buckets.values) {
                    if (bucket.size < 2) continue
                    if (rule.joinsSameContainer) {
                        for (i in bucket.indices) {
                            for (j in i + 1 until bucket.size) addPair(bucket[i], bucket[j])
                        }
                    } else {
                        // A large bucket can consist entirely of rows of one statement, especially for bridge rules.
                        val containers = bucket.groupBy { observations[it].container }.values.toList()
                        for (i in containers.indices) {
                            for (j in i + 1 until containers.size) {
                                for (a in containers[i]) {
                                    for (b in containers[j]) addPair(a, b)
                                }
                            }
                        }
                    }
                }
            }
        }.sortedWith(compareBy(Candidate::rank, Candidate::distance, Candidate::a, Candidate::b))

    /**
     * One rank's [candidates] (nearest first) reordered so the pairs of a largest one-to-one set of them come first.
     * Taking the nearest pair first can cross two records of a run (three like payments a day apart, each nearer to
     * its neighbour's partner), leaving one record with nobody and its partner stranded; a pair is only worth
     * leaving out when no other arrangement of the unjoined records pairs more of them. Only records not yet in a
     * group are matched this way: a record already in one still has to pair with all of it ([canJoin]).
     */
    private fun maximumMatchingFirst(
        candidates: List<Candidate>,
        groups: Groups,
    ): List<Candidate> {
        if (!rules[candidates.first().rank].pairsOneToOne) return candidates

        fun free(i: Int) = groups.members(groups.find(i)).size == 1
        val usable = candidates.filter { free(it.a) && free(it.b) }
        if (usable.size < 2) return candidates
        val neighbours = mutableMapOf<Int, MutableList<Int>>()
        for (c in usable) {
            neighbours.getOrPut(c.a) { mutableListOf() } += c.b
            neighbours.getOrPut(c.b) { mutableListOf() } += c.a
        }
        val mate = mutableMapOf<Int, Int>()
        for (c in usable) {
            if (c.a !in mate && c.b !in mate) {
                mate[c.a] = c.b
                mate[c.b] = c.a
            }
        }
        var budget = MATCHING_WORK_LIMIT

        fun augment(
            from: Int,
            seen: MutableSet<Int>,
            depth: Int,
        ): Boolean {
            for (next in neighbours.getValue(from)) {
                if (--budget < 0 || depth > MATCHING_DEPTH_LIMIT) return false
                if (!seen.add(next)) continue
                val partner = mate[next]
                if (partner == null || augment(partner, seen, depth + 1)) {
                    mate[from] = next
                    mate[next] = from
                    return true
                }
            }
            return false
        }
        for (start in neighbours.keys.sorted()) {
            if (start !in mate) augment(start, mutableSetOf(start), 0)
        }
        val (matched, rest) = candidates.partition { mate[it.a] == it.b }
        return matched + rest
    }

    private fun admits(
        rule: PairRule,
        a: MatchObservation,
        b: MatchObservation,
    ): Boolean = rule.joinsSameContainer || a.container != b.container

    /** Whether some rule pairs [a] and [b]. */
    private fun paired(
        a: MatchObservation,
        b: MatchObservation,
    ): Boolean =
        rules.any { rule ->
            val bucket = rule.bucket(a)
            bucket != null && admits(rule, a, b) && bucket == rule.bucket(b) && rule.distance(a, b) != null
        }

    /**
     * Whether the groups [x] and [y] may become one. Pinned observations stay where a person put them, but one that
     * joins them must still pair with each of them:
     * a pin keeps its own observations together, it does not let an unrelated record of another movement chain on
     * (a pinned Revolut row and a Coinbase deposit pulling in a Marcus withdrawal of the same amount). Pins still count
     * for splits and for each other.
     */
    private fun canJoin(
        observations: List<MatchObservation>,
        x: List<Int>,
        y: List<Int>,
        pinned: Map<Int, TransactionId>,
        apart: Set<Set<Int>>,
    ): Boolean {
        val pinX = x.firstNotNullOfOrNull { pinned[it] }
        val pinY = y.firstNotNullOfOrNull { pinned[it] }
        val pinsConflict = pinX != null && pinY != null && pinX != pinY
        return !pinsConflict &&
            x.all { i ->
                y.all { j ->
                    setOf(i, j) !in apart && ((i in pinned && j in pinned) || paired(observations[i], observations[j]))
                }
            }
    }

    /**
     * Each group's transaction. A pinned group keeps its pin. The others keep the existing transaction most of their
     * members are on now, so ids stay stable: groups claim transactions most members first, then in the matcher's
     * order, and each transaction goes to one group. A group left without one gets a new transaction.
     */
    private fun targetsOf(
        observations: List<MatchObservation>,
        groups: Groups,
        pinned: Map<Int, TransactionId>,
    ): Map<Int, TransactionTarget> {
        val roots = observations.indices.map { groups.find(it) }.distinct()
        val targets = mutableMapOf<Int, TransactionTarget>()
        val taken = mutableSetOf<TransactionId>()
        for (root in roots) {
            val pin = groups.members(root).firstNotNullOfOrNull { pinned[it] } ?: continue
            targets[root] = TransactionTarget.Existing(pin)
            taken += pin
        }

        data class Claim(
            val root: Int,
            val first: Int,
            val transaction: TransactionId,
            val members: Int,
        )
        val claims =
            roots
                .filter { it !in targets }
                .flatMap { root ->
                    val members = groups.members(root)
                    members
                        .mapNotNull { observations[it].current }
                        .groupingBy { it }
                        .eachCount()
                        .map { (transaction, count) -> Claim(root, root, transaction, count) }
                }.sortedWith(
                    compareByDescending<Claim> { it.members }
                        .thenBy { it.first }
                        .thenBy { it.transaction.id }
                        .thenBy { it.transaction is TradeId },
                )
        for (claim in claims) {
            if (claim.root in targets || claim.transaction in taken) continue
            targets[claim.root] = TransactionTarget.Existing(claim.transaction)
            taken += claim.transaction
        }
        for (root in roots) {
            if (root !in targets) targets[root] = TransactionTarget.New(observations[groups.members(root).min()].ref)
        }
        return targets
    }

    /** Union-find over observation indices; the root of a group is its first member, so groups read in order. */
    private class Groups(
        size: Int,
    ) {
        private val parent = IntArray(size) { it }
        private val members = Array(size) { mutableListOf(it) }

        fun find(i: Int): Int {
            var root = i
            while (parent[root] != root) root = parent[root]
            var node = i
            while (parent[node] != root) {
                val next = parent[node]
                parent[node] = root
                node = next
            }
            return root
        }

        fun members(root: Int): List<Int> = members[root]

        fun union(
            a: Int,
            b: Int,
        ) {
            val x = find(a)
            val y = find(b)
            if (x == y) return
            val (keep, drop) = if (x < y) x to y else y to x
            parent[drop] = keep
            members[keep] += members[drop]
            members[keep].sort()
            members[drop] = mutableListOf()
        }
    }

    companion object {
        /** Bounds on the search for a larger set of pairs, so a huge bucket of like records costs a fixed amount. */
        private const val MATCHING_WORK_LIMIT = 2_000_000
        private const val MATCHING_DEPTH_LIMIT = 200

        /**
         * The matcher's order of observations: by record key, then slot. Only observations of records with the same
         * key (identical rows) fall through to their owner, and those are interchangeable: whichever comes first, the
         * transactions have the same values.
         */
        private val ORDER: Comparator<MatchObservation> =
            compareBy<MatchObservation> { it.recordKey }
                .thenBy { it.ref.slot }
                .thenBy { it.ref.owner.orderTag() }
                .thenBy { it.ref.owner.orderId() }

        private fun ObservationOwner.orderTag(): Int =
            when (this) {
                is ObservationOwner.Record -> 0
                is ObservationOwner.Group -> 1
                is ObservationOwner.Manual -> 2
            }

        private fun ObservationOwner.orderId(): Long =
            when (this) {
                is ObservationOwner.Record -> recordId.id
                is ObservationOwner.Group -> groupId.id
                is ObservationOwner.Manual -> entitySourceId
            }
    }
}

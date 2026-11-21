package com.moneymanager.observationmatcher

import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.observation.ImportRecordId
import com.moneymanager.domain.model.observation.ObservationAttribute
import com.moneymanager.domain.model.observation.ObservationContent
import com.moneymanager.domain.model.observation.ObservationId
import com.moneymanager.domain.model.observation.ObservationOwner
import com.moneymanager.domain.model.observation.ObservationPin
import com.moneymanager.domain.model.observation.ObservationSplit
import com.moneymanager.domain.model.observation.ObservedAssetId
import com.moneymanager.domain.model.observation.ObservedValues
import com.moneymanager.importengineapi.DedupePolicy
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class ObservationMatcherTest {
    @Test
    fun `a repeated unique key is one transaction whatever else changed`() {
        val a = transfer(1, "csv-a", "k-a", description = "Coffee", key = "T1")
        val b = transfer(2, "csv-b", "k-b", description = "Coffee shop", amount = 4, key = "T1")
        val c = transfer(3, "csv-b", "k-c", description = "Coffee", key = "T2")

        val result = matcher(UNIQUE).match(listOf(a, b, c))

        assertSameTransaction(result, a, b)
        assertSeparate(result, a, c)
        assertEquals(listOf("unique key"), result.joins.map { it.rule })
    }

    @Test
    fun `a unique key repeated within one file is that row again`() {
        val a = transfer(1, "csv-a", "k-a", key = "T1")
        val b = transfer(2, "csv-a", "k-b", key = "T1")

        assertSameTransaction(matcher(UNIQUE).match(listOf(a, b)), a, b)
    }

    @Test
    fun `an api id is one transaction across sessions`() {
        val a = transfer(1, "session-1", "k-a", apiId = "tx-9")
        val b = transfer(2, "session-2", "k-b", apiId = "tx-9", hour = 3)

        val result = matcher(API).match(listOf(a, b))

        assertSameTransaction(result, a, b)
        assertEquals("api id", result.joins.single().rule)
    }

    @Test
    fun `api items with the same fields either way round are one unless their ids differ`() {
        val a = transfer(1, "session-1", "k-a")
        val b = transfer(2, "session-1", "k-b", source = 20, target = 10)
        val c = transfer(3, "session-2", "k-c", apiId = "x")
        val d = transfer(4, "session-2", "k-d", apiId = "y")

        val result = matcher(API).match(listOf(a, b, c, d))

        // a and b have no id, so they join; c joins them; d's own id differs from c's, so it stays apart.
        assertSameTransaction(result, a, b)
        assertSameTransaction(result, a, c)
        assertSeparate(result, a, d)
    }

    @Test
    fun `an exact duplicate across files is one transaction but two like rows of one file are two`() {
        val a = transfer(1, "csv-a", "k-a")
        val b = transfer(2, "csv-a", "k-b")
        val c = transfer(3, "csv-b", "k-c")

        val result = matcher(FUZZY).match(listOf(a, b, c))

        assertSeparate(result, a, b)
        val joined = listOf(a, b).filter { result.assignments[it.ref] == result.assignments[c.ref] }
        assertEquals(1, joined.size, "the other file's row joins exactly one of the two")
        assertEquals("exact", result.joins.single().rule)
    }

    @Test
    fun `a fuzzy duplicate joins the nearest record and not the first`() {
        // Two £1,000 deposits a day apart in one export; a re-export of the later one, an hour off.
        val early = transfer(1, "csv-a", "k-1", description = "GBP Deposit", amount = 1000)
        val late = transfer(2, "csv-a", "k-2", description = "GBP Deposit", amount = 1000, hour = 24)
        val again = transfer(3, "csv-b", "k-0", description = "GBP Deposit.", amount = 1000, hour = 25)

        val result = matcher(FUZZY).match(listOf(early, late, again))

        assertSameTransaction(result, late, again)
        assertSeparate(result, early, again)
        assertEquals("fuzzy", result.joins.single().rule)
    }

    @Test
    fun `fuzzy needs a shared end and the tolerance and alike descriptions`() {
        val base = transfer(1, "csv-a", "k-1", description = "Tesco Stores 1234")
        val otherAccounts = transfer(2, "csv-b", "k-2", description = "Tesco Stores 1234", source = 30, target = 40, hour = 1)
        val tooLate = transfer(3, "csv-c", "k-3", description = "Tesco Stores 1234", hour = 24 * 4)
        val otherShop = transfer(4, "csv-d", "k-4", description = "Sainsbury's", hour = 1)
        val excluded = transfer(5, "csv-e", "k-5", description = "Tesco Stores 1234", hour = 1, excluded = true)

        val result = matcher(FUZZY).match(listOf(base, otherAccounts, tooLate, otherShop, excluded))

        assertEquals(
            5,
            result.assignments.values
                .toSet()
                .size,
        )
    }

    @Test
    fun `a group never chains near neighbours that are not near each other`() {
        val a = transfer(1, "csv-a", "k-a", description = "Rent")
        val b = transfer(2, "csv-b", "k-b", description = "Rent", hour = 48)
        val c = transfer(3, "csv-c", "k-c", description = "Rent", hour = 96)

        val result = matcher(FUZZY).match(listOf(a, b, c))

        assertSeparate(result, a, c)
        assertEquals(
            2,
            result.assignments.values
                .toSet()
                .size,
        )
    }

    @Test
    fun `families without dedupe or of another strategy are never paired`() {
        val a = transfer(1, "csv-a", "k-a", family = "Monzo")
        val b = transfer(2, "csv-b", "k-b", family = "Santander")
        val c = transfer(3, "csv-c", "k-c", family = "None")

        val result = matcher(mapOf("Monzo" to FUZZY, "Santander" to FUZZY, "None" to DedupePolicy.None)).match(listOf(a, b, c))

        assertEquals(
            3,
            result.assignments.values
                .toSet()
                .size,
        )
    }

    @Test
    fun `a split keeps two matching observations apart`() {
        val a = transfer(1, "csv-a", "k-a", stored = 1)
        val b = transfer(2, "csv-b", "k-b", stored = 2)

        val result = matcher(FUZZY).match(listOf(a, b), splits = listOf(ObservationSplit.of(ObservationId(2), ObservationId(1))))

        assertSeparate(result, a, b)
    }

    @Test
    fun `pins fix observations to their transaction whatever the rules say`() {
        val a = transfer(1, "csv-a", "k-a", stored = 1, current = 100)
        val b = transfer(2, "csv-b", "k-b", stored = 2, current = 200, description = "Something else entirely")
        val pins = listOf(ObservationPin(ObservationId(1), TransferId(100)), ObservationPin(ObservationId(2), TransferId(100)))

        val result = matcher(FUZZY).match(listOf(a, b), pins = pins)

        assertEquals(TransactionTarget.Existing(TransferId(100)), result.assignments[b.ref])
        assertEquals(TransactionTarget.Existing(TransferId(100)), result.assignments[a.ref])
        assertEquals(setOf<TransactionId>(TransferId(200)), result.released)
        assertEquals(listOf(AssignmentChange(b.ref, TransferId(200), TransactionTarget.Existing(TransferId(100)))), result.changes)
    }

    @Test
    fun `a record pairing with one pinned observation does not chain onto the others pinned with it`() {
        val a = transfer(1, "csv-a", "k-a", stored = 1, current = 100)
        val b = transfer(2, "csv-b", "k-b", stored = 2, current = 100, description = "Something else entirely")
        val c = transfer(3, "csv-c", "k-c")
        val pins = listOf(ObservationPin(ObservationId(1), TransferId(100)), ObservationPin(ObservationId(2), TransferId(100)))

        val result = matcher(FUZZY).match(listOf(a, b, c), pins = pins)

        assertSameTransaction(result, a, b)
        assertSeparate(result, a, c)
    }

    @Test
    fun `a run of like payments pairs one to one rather than crossing the nearest pairs`() {
        // Days: a = 0, 8, 11, 14 and b = 3, 11, 13, 17. Nearest-first pairs a11 with b11 and a14 with b13, stranding a8 and b17;
        // pairing a0-b3, a8-b11, a11-b13 and a14-b17 leaves nobody out.
        val a = listOf(0, 8, 11, 14).mapIndexed { i, day -> transfer(i + 1L, "bank-a", "a$i", hour = day * 24) }
        val b = listOf(3, 11, 13, 17).mapIndexed { i, day -> transfer(i + 11L, "bank-b", "b$i", hour = day * 24) }
        val withinThreeDays =
            object : PairRule {
                override val name = "within three days"
                override val joinsSameContainer = false
                override val pairsOneToOne = true

                override fun bucket(observation: MatchObservation): Any = 1

                override fun distance(
                    a: MatchObservation,
                    b: MatchObservation,
                ): Long? {
                    val seconds =
                        (a.content.values as ObservedValues.Transfer).timestamp - (b.content.values as ObservedValues.Transfer).timestamp
                    return seconds.absoluteValue.inWholeSeconds.takeIf { it <= 3.days.inWholeSeconds }
                }
            }

        val result = ObservationMatcher(listOf(withinThreeDays)).match(a + b)

        (a + b).forEach { o -> assertEquals(2, (a + b).count { result.assignments[it.ref] == result.assignments[o.ref] }) }
        assertSameTransaction(result, a[0], b[0])
        assertSameTransaction(result, a[1], b[1])
        assertSameTransaction(result, a[2], b[2])
        assertSameTransaction(result, a[3], b[3])
    }

    @Test
    fun `two observations pinned to different transactions never join`() {
        val a = transfer(1, "csv-a", "k-a", stored = 1, current = 100)
        val b = transfer(2, "csv-b", "k-b", stored = 2, current = 200)

        val result =
            matcher(FUZZY).match(
                listOf(a, b),
                pins = listOf(ObservationPin(ObservationId(1), TransferId(100)), ObservationPin(ObservationId(2), TransferId(200))),
            )

        assertSeparate(result, a, b)
        assertTrue(result.changes.isEmpty())
    }

    @Test
    fun `a new duplicate joins the existing transaction and changes nothing else`() {
        val stored = transfer(1, "csv-a", "k-a", stored = 1, current = 100)
        val incoming = transfer(2, "csv-b", "k-b")

        val result = matcher(FUZZY).match(listOf(stored, incoming))

        assertEquals(listOf(AssignmentChange(incoming.ref, null, TransactionTarget.Existing(TransferId(100)))), result.changes)
        assertTrue(result.released.isEmpty())
    }

    @Test
    fun `an unmatched new observation gets a new transaction anchored on itself`() {
        val incoming = transfer(1, "csv-a", "k-a")

        val result = matcher(FUZZY).match(listOf(incoming))

        assertEquals(listOf(AssignmentChange(incoming.ref, null, TransactionTarget.New(incoming.ref))), result.changes)
    }

    @Test
    fun `When a transaction splits the larger part keeps its id`() {
        // a, b and c were one transaction; c now says something else.
        val a = transfer(1, "csv-a", "k-a", stored = 1, current = 100)
        val b = transfer(2, "csv-b", "k-b", stored = 2, current = 100)
        val c = transfer(3, "csv-c", "k-c", stored = 3, current = 100, amount = 99)

        val result = matcher(FUZZY).match(listOf(a, b, c))

        assertEquals(TransactionTarget.Existing(TransferId(100)), result.assignments[a.ref])
        assertEquals(listOf(AssignmentChange(c.ref, TransferId(100), TransactionTarget.New(c.ref))), result.changes)
    }

    @Test
    fun `the scope reaches what shares a transaction and what a rule pairs transitively`() {
        val incoming = transfer(1, "csv-new", "k-new")
        val near = transfer(2, "csv-a", "k-a", stored = 2, current = 100, hour = 48)
        val sameTransaction = transfer(3, "csv-b", "k-b", stored = 3, current = 100, description = "Other", hour = 72)
        val pairedWithThat = transfer(4, "csv-c", "k-c", stored = 4, current = 200, description = "Other", hour = 120)
        val unrelated = transfer(5, "csv-d", "k-d", stored = 5, current = 300, amount = 7)
        val removed = transfer(6, "csv-e", "k-e", stored = 6, current = 400, amount = 8)
        val leftBehind = transfer(7, "csv-f", "k-f", stored = 7, current = 400, amount = 9)

        val scope =
            matcher(FUZZY).scopeOf(
                changed = listOf(incoming),
                removed = listOf(removed),
                stored = listOf(near, sameTransaction, pairedWithThat, unrelated, removed, leftBehind),
            )

        assertEquals(
            setOf(incoming, near, sameTransaction, pairedWithThat, leftBehind).map { it.ref }.toSet(),
            scope.map { it.ref }.toSet(),
        )
    }

    @Test
    fun `permuting the input never changes the result`() {
        val policies = mapOf(FAMILY to FUZZY, "Api" to DedupePolicy.ApiMultiKey(), "Ids" to DedupePolicy.UniqueIdentifier())
        val scope =
            listOf(
                // Three like deposits over two files, where file order would hand the re-export to the wrong one.
                transfer(1, "csv-a", "k-1", description = "GBP Deposit", amount = 1000, stored = 1, current = 100),
                transfer(2, "csv-a", "k-2", description = "GBP Deposit", amount = 1000, hour = 30, stored = 2, current = 101),
                transfer(3, "csv-b", "k-3", description = "GBP Deposit", amount = 1000, hour = 20),
                transfer(4, "csv-b", "k-4", description = "GBP Deposit", amount = 1000, hour = 10),
                transfer(5, "csv-c", "k-5", description = "GBP Deposit.", amount = 1000, hour = 15),
                // Equal distances: only the record keys decide.
                transfer(6, "csv-a", "k-6", description = "Tie", amount = 5, hour = 100),
                transfer(7, "csv-b", "k-7", description = "Tie", amount = 5, hour = 99),
                transfer(8, "csv-c", "k-8", description = "Tie", amount = 5, hour = 101),
                transfer(9, "csv-d", "k-9", description = "Tie", amount = 5, hour = 100, stored = 9, current = 102),
                transfer(10, "s-1", "k-10", family = "Api", apiId = "a"),
                transfer(11, "s-2", "k-11", family = "Api", apiId = "a", hour = 2),
                transfer(12, "s-2", "k-12", family = "Api", source = 20, target = 10),
                transfer(13, "f-1", "k-13", family = "Ids", key = "x", stored = 13, current = 103),
                transfer(14, "f-2", "k-14", family = "Ids", key = "x", amount = 2),
                transfer(15, "csv-e", "k-15", description = "Tie", amount = 5, hour = 100, stored = 15),
            )
        val splits = listOf(ObservationSplit.of(ObservationId(9), ObservationId(15)))
        val matcher = matcher(policies)
        val expected = matcher.match(scope, splits = splits)

        for (permutation in permutationsOf(scope)) {
            assertEquals(expected, matcher.match(permutation, splits = splits))
        }
        // A sanity check that the case is not trivial: deposits 1 and 2 both found a re-export.
        assertTrue(expected.joins.size >= 5, "${expected.joins}")
    }

    /** Rotations and reversals plus a deterministic shuffle sequence: enough orders to catch any order dependence. */
    private fun <T> permutationsOf(items: List<T>): List<List<T>> {
        val rotations = items.indices.map { items.drop(it) + items.take(it) }
        val shuffles = (1..40).map { seed -> items.shuffled(Random(seed)) }
        return rotations + rotations.map { it.reversed() } + shuffles
    }

    private fun assertSameTransaction(
        result: MatchResult,
        a: MatchObservation,
        b: MatchObservation,
    ) = assertEquals(result.assignments[a.ref], result.assignments[b.ref], "${a.recordKey} and ${b.recordKey} should be one transaction")

    private fun assertSeparate(
        result: MatchResult,
        a: MatchObservation,
        b: MatchObservation,
    ) = assertTrue(result.assignments[a.ref] != result.assignments[b.ref], "${a.recordKey} and ${b.recordKey} should be apart")

    private fun matcher(policy: DedupePolicy): ObservationMatcher = matcher(mapOf(FAMILY to policy))

    private fun matcher(policies: Map<String, DedupePolicy>): ObservationMatcher = ObservationMatcher(SameFamilyRules.forPolicies(policies))

    @Suppress("LongParameterList")
    private fun transfer(
        record: Long,
        container: String,
        recordKey: String,
        description: String = "Payment",
        amount: Long = 10,
        hour: Int = 0,
        source: Long = 10,
        target: Long = 20,
        family: String = FAMILY,
        key: String? = null,
        apiId: String? = null,
        stored: Long? = null,
        current: Long? = null,
        excluded: Boolean = false,
    ) = MatchObservation(
        ref = ObservationRef(ObservationOwner.Record(ImportRecordId(record)), "main"),
        storedId = stored?.let(::ObservationId),
        current = current?.let(::TransferId),
        family = family,
        container = container,
        recordKey = recordKey,
        content =
            ObservationContent(
                values =
                    ObservedValues.Transfer(
                        timestamp = START + hour.hours,
                        description = description,
                        sourceAccountId = AccountId(source),
                        targetAccountId = AccountId(target),
                        assetId = ObservedAssetId(1),
                        amount = BigInteger(amount),
                    ),
                attributes =
                    if (excluded) {
                        setOf(ObservationAttribute(AttributeTypeId(WellKnownIds.EXCLUDED_ATTR_TYPE_ID), "", "declined"))
                    } else {
                        emptySet()
                    },
            ),
        identity = RecordIdentity(uniqueKey = key?.let { mapOf("id" to it) }.orEmpty(), apiId = apiId),
    )

    private companion object {
        const val FAMILY = "Bank"
        val START = Instant.parse("2026-03-02T09:00:00Z")
        val FUZZY = DedupePolicy.FuzzyAllFields(dateTolerance = 3.days)
        val UNIQUE = DedupePolicy.UniqueIdentifier()
        val API = DedupePolicy.ApiMultiKey()
    }
}

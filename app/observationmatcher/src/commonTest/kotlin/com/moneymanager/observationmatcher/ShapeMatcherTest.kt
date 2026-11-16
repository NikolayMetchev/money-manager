package com.moneymanager.observationmatcher

import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.observation.ImportRecordId
import com.moneymanager.domain.model.observation.ObservationAttribute
import com.moneymanager.domain.model.observation.ObservationContent
import com.moneymanager.domain.model.observation.ObservationEnd
import com.moneymanager.domain.model.observation.ObservationFacts
import com.moneymanager.domain.model.observation.ObservationOwner
import com.moneymanager.domain.model.observation.ObservedAssetId
import com.moneymanager.domain.model.observation.ObservedValues
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class ShapeMatcherTest {
    @Test
    fun `a conduit statement excludes its placeholder and supersedes the card chain past the conduit`() {
        val records = payPalCase()

        val link = matcher.match(records.flatMap { it.observations }).links.single()

        assertEquals(ShapeReason.CONDUIT_STATEMENT, link.reason)
        assertEquals(setOf(key("paypal-deposit", MAIN), key("card-payment", MAIN)), link.matched.keys(records))
        assertEquals(setOf(key("paypal-deposit", MAIN), key("card-payment", "conduit/0")), link.excluded.keys(records))
        // Today's balances: the card pays once, PayPal nets to zero, and PayPal's own payee is paid (the card chain's
        // guessed merchant is not).
        assertEquals(
            mapOf(CARD to -50L, PAYPAL to 0L, PLACEHOLDER to 0L, MERCHANT to 0L, PAYPAL_PAYEE to 50L),
            balances(records, matcher.match(records.flatMap { it.observations })),
        )
    }

    @Test
    fun `a curve row naming only its card supersedes the card chain and keeps its own payee`() {
        val card = record("card", "card-statement", chain(CARD, CURVE, MERCHANT, 30))
        val curve = record("curve", "curve-export", chain(PLACEHOLDER, CURVE, CURVE_MERCHANT, 30, placeholderFunded = true))
        val records = listOf(card, curve)

        val result = matcher.match(records.flatMap { it.observations })

        val link = result.links.single()
        assertEquals(setOf(key("curve", MAIN), key("card", "conduit/0")), link.excluded.keys(records))
        assertEquals(mapOf(CARD to -30L, CURVE to 0L, PLACEHOLDER to 0L, MERCHANT to 0L, CURVE_MERCHANT to 30L), balances(records, result))
    }

    @Test
    fun `a chain funded by a placeholder is never the route of a card through the conduit`() {
        val a = record("curve-a", "curve-export-1", chain(PLACEHOLDER, CURVE, MERCHANT, 30, placeholderFunded = true))
        val b = record("curve-b", "curve-export-2", chain(PLACEHOLDER, CURVE, MERCHANT, 30, placeholderFunded = true))

        assertTrue(matcher.match((a.observations + b.observations)).links.isEmpty())
    }

    @Test
    fun `a refund through the conduit matches the placeholder leaving it`() {
        val card = record("card-refund", "card-statement", chain(CARD, PAYPAL, MERCHANT, 20, refund = true))
        val deposit = record("paypal-refund", "paypal-export", leg(MAIN, PAYPAL, PLACEHOLDER, 20, placeholder = ObservationEnd.TARGET))
        val received = record("paypal-received", "paypal-export", leg(MAIN, PAYPAL_PAYEE, PAYPAL, 20))
        val records = listOf(card, deposit, received)

        val result = matcher.match(records.flatMap { it.observations })

        assertEquals(
            setOf(key("paypal-refund", MAIN), key("card-refund", "conduit/0")),
            result.links
                .single()
                .excluded
                .keys(records),
        )
        assertEquals(mapOf(CARD to 20L, PAYPAL to 0L, PLACEHOLDER to 0L, MERCHANT to 0L, PAYPAL_PAYEE to -20L), balances(records, result))
    }

    @Test
    fun `the outer conduit matches when the chain runs through two`() {
        val card =
            record(
                "card",
                "card-statement",
                leg(MAIN, CARD, CURVE, 40),
                leg("conduit/0", CURVE, PAYPAL, 40),
                leg("conduit/1", PAYPAL, MERCHANT, 40),
            )
        val deposit = record("paypal-deposit", "paypal-export", leg(MAIN, PLACEHOLDER, PAYPAL, 40, placeholder = ObservationEnd.SOURCE))
        val records = listOf(card, deposit)

        val link = matcher.match(records.flatMap { it.observations }).links.single()

        assertEquals(setOf(key("paypal-deposit", MAIN), key("card", "conduit/0")), link.matched.keys(records))
        assertEquals(setOf(key("paypal-deposit", MAIN), key("card", "conduit/1")), link.excluded.keys(records))
    }

    @Test
    fun `a gross leg is excluded against the net leg and fee of another source`() {
        val api = record("api-withdrawal", "api-session", leg(MAIN, EXCHANGE, WALLET, 99), leg(FEE, EXCHANGE, EXCHANGE_FEES, 1))
        val csv = record("csv-withdrawal", "csv-export", leg(MAIN, EXCHANGE, PLACEHOLDER, 100, placeholder = ObservationEnd.TARGET))
        val records = listOf(api, csv)

        val result = matcher.match(records.flatMap { it.observations })

        val link = result.links.single()
        assertEquals(ShapeReason.GROSS_ITEMISED, link.reason)
        assertEquals(setOf(key("csv-withdrawal", MAIN)), link.excluded.keys(records))
        assertEquals(mapOf(EXCHANGE to -100L, WALLET to 99L, EXCHANGE_FEES to 1L, PLACEHOLDER to 0L), balances(records, result))
    }

    @Test
    fun `a gross leg naming the same far end matches but not one naming another`() {
        val api = record("api", "api-session", leg(MAIN, EXCHANGE, WALLET, 99), leg(FEE, EXCHANGE, EXCHANGE_FEES, 1))
        val same = record("same", "csv-1", leg(MAIN, EXCHANGE, WALLET, 100))
        val other = record("other", "csv-2", leg(MAIN, EXCHANGE, MERCHANT, 100, hour = 1))

        val result = matcher.match(listOf(api, same, other).flatMap { it.observations })

        assertEquals(
            setOf(key("same", MAIN)),
            result.links
                .single()
                .excluded
                .keys(listOf(api, same, other)),
        )
    }

    @Test
    fun `a gross leg that does not add up stays counted`() {
        val api = record("api", "api-session", leg(MAIN, EXCHANGE, WALLET, 99), leg(FEE, EXCHANGE, EXCHANGE_FEES, 2))
        val csv = record("csv", "csv-export", leg(MAIN, EXCHANGE, PLACEHOLDER, 100, placeholder = ObservationEnd.TARGET))

        assertTrue(matcher.match((api.observations + csv.observations)).links.isEmpty())
    }

    @Test
    fun `a conduit statement leg matches card movements that sum to it`() {
        val authorised = record("uber-1", "card-statement", chain(CARD, PAYPAL, MERCHANT, 2158))
        val topUp = record("uber-2", "card-statement", chain(CARD, PAYPAL, MERCHANT, 27, hour = 20))
        val deposit = record("paypal", "paypal-export", leg(MAIN, PLACEHOLDER, PAYPAL, 2185, placeholder = ObservationEnd.SOURCE, hour = 2))
        val payment = record("paypal-payment", "paypal-export", leg(MAIN, PAYPAL, PAYPAL_PAYEE, 2185, hour = 2))
        val records = listOf(authorised, topUp, deposit, payment)

        val result = matcher.match(records.flatMap { it.observations })

        val link = result.links.single()
        assertEquals(ShapeReason.SPLIT_CONDUIT, link.reason)
        assertEquals(setOf(key("paypal", MAIN), key("uber-1", "conduit/0"), key("uber-2", "conduit/0")), link.excluded.keys(records))
        assertEquals(
            mapOf(CARD to -2185L, PAYPAL to 0L, PLACEHOLDER to 0L, MERCHANT to 0L, PAYPAL_PAYEE to 2185L),
            balances(records, result),
        )
    }

    @Test
    fun `a split nets a refund against a charge`() {
        val charge = record("charge", "card-statement", chain(CARD, PAYPAL, MERCHANT, 10784))
        val refund = record("refund", "card-statement", chain(CARD, PAYPAL, MERCHANT, 3, refund = true, hour = 5))
        val deposit =
            record("paypal", "paypal-export", leg(MAIN, PLACEHOLDER, PAYPAL, 10781, placeholder = ObservationEnd.SOURCE, hour = 1))
        val payment = record("paypal-payment", "paypal-export", leg(MAIN, PAYPAL, PAYPAL_PAYEE, 10781, hour = 1))
        val records = listOf(charge, refund, deposit, payment)

        val result = matcher.match(records.flatMap { it.observations })

        assertEquals(ShapeReason.SPLIT_CONDUIT, result.links.single().reason)
        assertEquals(
            mapOf(CARD to -10781L, PAYPAL to 0L, PLACEHOLDER to 0L, MERCHANT to 0L, PAYPAL_PAYEE to 10781L),
            balances(records, result),
        )
    }

    @Test
    fun `a single match beats a split`() {
        val exact = record("exact", "card-statement", chain(CARD, PAYPAL, MERCHANT, 30, hour = 10))
        val partA = record("part-a", "card-statement-2", chain(CARD, PAYPAL, MERCHANT, 10))
        val partB = record("part-b", "card-statement-2", chain(CARD, PAYPAL, MERCHANT, 20))
        val deposit = record("paypal", "paypal-export", leg(MAIN, PLACEHOLDER, PAYPAL, 30, placeholder = ObservationEnd.SOURCE))
        val records = listOf(exact, partA, partB, deposit)

        val link = matcher.match(records.flatMap { it.observations }).links.single()

        assertEquals(ShapeReason.CONDUIT_STATEMENT, link.reason)
        assertEquals(setOf(key("paypal", MAIN), key("exact", MAIN)), link.matched.keys(records))
    }

    @Test
    fun `each placeholder takes the nearest chain movement and each movement one placeholder`() {
        val early = record("card-early", "card-statement", chain(CARD, PAYPAL, MERCHANT, 30))
        val late = record("card-late", "card-statement", chain(CARD, PAYPAL, MERCHANT, 30, hour = 30))
        val first = record("paypal-1", "paypal-export", leg(MAIN, PLACEHOLDER, PAYPAL, 30, placeholder = ObservationEnd.SOURCE, hour = 26))
        val second = record("paypal-2", "paypal-export", leg(MAIN, PLACEHOLDER, PAYPAL, 30, placeholder = ObservationEnd.SOURCE, hour = 2))
        val records = listOf(early, late, first, second)

        val pairs =
            matcher
                .match(records.flatMap { it.observations })
                .links
                .map { it.matched.keys(records) }
                .toSet()

        assertEquals(
            setOf(
                setOf(key("paypal-2", MAIN), key("card-early", MAIN)),
                setOf(key("paypal-1", MAIN), key("card-late", MAIN)),
            ),
            pairs,
        )
    }

    @Test
    fun `records a source excluded or outside the window or of one file never match`() {
        val card = record("card", "card-statement", chain(CARD, PAYPAL, MERCHANT, 30))
        val excluded =
            record("excluded", "paypal-export", leg(MAIN, PLACEHOLDER, PAYPAL, 30, placeholder = ObservationEnd.SOURCE, excluded = true))
        val far = record("far", "paypal-export", leg(MAIN, PLACEHOLDER, PAYPAL, 30, placeholder = ObservationEnd.SOURCE, hour = 24 * 5))
        val sameFile = record("same-file", "card-statement", leg(MAIN, PLACEHOLDER, PAYPAL, 30, placeholder = ObservationEnd.SOURCE))

        assertTrue(matcher.match(listOf(card, excluded, far, sameFile).flatMap { it.observations }).links.isEmpty())
    }

    @Test
    fun `the own fee of an excluded placeholder is excluded with it`() {
        val card = record("card", "card-statement", chain(CARD, PAYPAL, MERCHANT, 30))
        val deposit =
            record(
                "paypal",
                "paypal-export",
                leg(MAIN, PLACEHOLDER, PAYPAL, 30, placeholder = ObservationEnd.SOURCE),
                leg(FEE, PAYPAL, EXCHANGE_FEES, 1),
            )

        val link = matcher.match((card.observations + deposit.observations)).links.single()

        assertEquals(
            setOf(key("paypal", MAIN), key("paypal", FEE), key("card", "conduit/0")),
            link.excluded.keys(listOf(card, deposit)),
        )
    }

    @Test
    fun `every import order gives the same links`() {
        val records =
            payPalCase() +
                record("curve-card", "card-statement-2", chain(CARD, CURVE, MERCHANT, 30, hour = 40)) +
                record("curve", "curve-export", chain(PLACEHOLDER, CURVE, CURVE_MERCHANT, 30, placeholderFunded = true, hour = 41)) +
                record("api", "api-session", leg(MAIN, EXCHANGE, WALLET, 99), leg(FEE, EXCHANGE, EXCHANGE_FEES, 1)) +
                record("gross", "csv-export", leg(MAIN, EXCHANGE, PLACEHOLDER, 100, placeholder = ObservationEnd.TARGET)) +
                record("uber-1", "card-statement-3", chain(CARD, PAYPAL, MERCHANT, 2158, hour = 100)) +
                record("uber-2", "card-statement-3", chain(CARD, PAYPAL, MERCHANT, 27, hour = 120)) +
                record(
                    "paypal-split",
                    "paypal-export-2",
                    leg(MAIN, PLACEHOLDER, PAYPAL, 2185, placeholder = ObservationEnd.SOURCE, hour = 102),
                ) +
                // Two like placeholders for one chain movement: whichever file came first, the same one is matched.
                record("dup-a", "paypal-export-3", leg(MAIN, PLACEHOLDER, PAYPAL, 7, placeholder = ObservationEnd.SOURCE, hour = 201)) +
                record("dup-b", "paypal-export-4", leg(MAIN, PLACEHOLDER, PAYPAL, 7, placeholder = ObservationEnd.SOURCE, hour = 199)) +
                record("dup-card", "card-statement-4", chain(CARD, PAYPAL, MERCHANT, 7, hour = 200))
        val containers = records.map { it.container }.distinct()

        fun linksInOrder(order: List<String>): Set<Pair<ShapeReason, Pair<Set<String>, Set<String>>>> {
            // Record ids come from the order files were imported in, as a database's would.
            val imported = order.flatMap { container -> records.filter { it.container == container } }
            val renumbered = imported.mapIndexed { i, r -> r.withId(1000L + i) }
            val scope = renumbered.flatMap { it.observations }.reversed()
            return matcher
                .match(scope)
                .links
                .map { it.reason to (it.matched.keys(renumbered) to it.excluded.keys(renumbered)) }
                .toSet()
        }

        val expected = linksInOrder(containers)
        assertEquals(5, expected.size)
        // dup-a and dup-b are equally near: the tie is broken by what they are, never by which file came first.
        assertEquals(
            1,
            expected.count { (_, sets) ->
                sets.first.any {
                    it.startsWith("dup-") && it.endsWith("\u0000main") &&
                        !it.startsWith("dup-card")
                }
            },
        )
        for (order in permutationsOf(containers).take(PERMUTATIONS)) {
            assertEquals(expected, linksInOrder(order), "import order $order")
        }
    }

    @Test
    fun `link changes are what was added and what went away`() {
        val records = payPalCase()
        val link = matcher.match(records.flatMap { it.observations }).links.single()

        assertTrue(exclusionLinkChanges(listOf(link), listOf(link)).isEmpty)
        assertEquals(ExclusionLinkChanges(add = listOf(link), remove = emptyList()), exclusionLinkChanges(emptyList(), listOf(link)))
        // The PayPal file is removed: its link goes with it, and the card chain counts again.
        val cardOnly = records.filter { it.container != "paypal-export" }
        val after = matcher.match(cardOnly.flatMap { it.observations }).links
        assertEquals(ExclusionLinkChanges(add = emptyList(), remove = listOf(link)), exclusionLinkChanges(listOf(link), after))
    }

    // ---- fixtures ----

    private class Leg(
        val slot: String,
        val source: AccountId,
        val target: AccountId,
        val amount: Long,
        val hour: Int,
        val placeholder: ObservationEnd?,
        val excluded: Boolean,
    )

    private class Record(
        val key: String,
        val container: String,
        val id: Long,
        val legs: List<Leg>,
    ) {
        val observations: List<MatchObservation> =
            legs.map { leg ->
                MatchObservation(
                    ref = ObservationRef(ObservationOwner.Record(ImportRecordId(id)), leg.slot),
                    storedId = null,
                    current = null,
                    family = container.substringBefore('-'),
                    container = container,
                    recordKey = key,
                    content =
                        ObservationContent(
                            values =
                                ObservedValues.Transfer(
                                    timestamp = T0 + leg.hour.hours,
                                    description = key,
                                    sourceAccountId = leg.source,
                                    targetAccountId = leg.target,
                                    assetId = GBP,
                                    amount = BigInteger(leg.amount),
                                ),
                            attributes =
                                if (leg.excluded) {
                                    setOf(ObservationAttribute(AttributeTypeId(WellKnownIds.EXCLUDED_ATTR_TYPE_ID), "", "deleted"))
                                } else {
                                    emptySet()
                                },
                            facts = ObservationFacts(placeholderEnds = setOfNotNull(leg.placeholder)),
                        ),
                )
            }

        fun withId(newId: Long) = Record(key, container, newId, legs)
    }

    private fun record(
        key: String,
        container: String,
        vararg legs: Leg,
    ) = Record(key, container, nextId++, legs.toList())

    private fun record(
        key: String,
        container: String,
        legs: List<Leg>,
    ) = Record(key, container, nextId++, legs)

    private var nextId = 1L

    private fun leg(
        slot: String,
        source: AccountId,
        target: AccountId,
        amount: Long,
        placeholder: ObservationEnd? = null,
        hour: Int = 0,
        excluded: Boolean = false,
    ) = Leg(slot, source, target, amount, hour, placeholder, excluded)

    /** A pass-through chain `funder -> conduit -> merchant`, or for a refund the other way. */
    private fun chain(
        funder: AccountId,
        conduit: AccountId,
        merchant: AccountId,
        amount: Long,
        refund: Boolean = false,
        placeholderFunded: Boolean = false,
        hour: Int = 0,
    ): List<Leg> {
        val placeholder =
            if (!placeholderFunded) {
                null
            } else if (refund) {
                ObservationEnd.TARGET
            } else {
                ObservationEnd.SOURCE
            }
        return if (refund) {
            listOf(leg(MAIN, conduit, funder, amount, placeholder, hour), leg("conduit/0", merchant, conduit, amount, hour = hour))
        } else {
            listOf(leg(MAIN, funder, conduit, amount, placeholder, hour), leg("conduit/0", conduit, merchant, amount, hour = hour))
        }
    }

    private fun payPalCase(): List<Record> =
        listOf(
            record("card-payment", "card-statement", chain(CARD, PAYPAL, MERCHANT, 50)),
            record("paypal-deposit", "paypal-export", leg(MAIN, PLACEHOLDER, PAYPAL, 50, placeholder = ObservationEnd.SOURCE, hour = 1)),
            record("paypal-payment", "paypal-export", leg(MAIN, PAYPAL, PAYPAL_PAYEE, 50, hour = 1)),
        )

    private fun key(
        record: String,
        slot: String,
    ) = "$record\u0000$slot"

    private fun Set<ObservationRef>.keys(records: List<Record>): Set<String> {
        val byOwner = records.associateBy { ObservationOwner.Record(ImportRecordId(it.id)) as ObservationOwner }
        return mapTo(mutableSetOf()) { key(byOwner.getValue(it.owner).key, it.slot) }
    }

    /** Each account's balance over the counted observations: what the links leave in. */
    private fun balances(
        records: List<Record>,
        result: ShapeMatchResult,
    ): Map<AccountId, Long> {
        val excluded = result.excluded
        val totals = mutableMapOf<AccountId, Long>()
        for (o in records.flatMap { it.observations }) {
            val values = o.content.values as ObservedValues.Transfer
            val amount = if (o.excluded || o.ref in excluded) 0L else values.amount.toLong()
            totals[values.sourceAccountId] = totals.getOrElse(values.sourceAccountId) { 0L } - amount
            totals[values.targetAccountId] = totals.getOrElse(values.targetAccountId) { 0L } + amount
        }
        return totals
    }

    private fun <T> permutationsOf(items: List<T>): Sequence<List<T>> =
        if (items.size <= 1) {
            sequenceOf(items)
        } else {
            items.indices.asSequence().flatMap { i ->
                permutationsOf(items.take(i) + items.drop(i + 1)).map { listOf(items[i]) + it }
            }
        }

    private companion object {
        const val MAIN = "main"
        const val FEE = "fee"
        const val PERMUTATIONS = 2000

        val matcher = ShapeMatcher(ShapeWindow.elapsed(3.days))
        val T0: Instant = Instant.parse("2026-03-02T10:00:00Z")
        val GBP = ObservedAssetId(1)

        val CARD = AccountId(1)
        val PAYPAL = AccountId(2)
        val PLACEHOLDER = AccountId(3)
        val MERCHANT = AccountId(4)
        val PAYPAL_PAYEE = AccountId(5)
        val CURVE = AccountId(6)
        val CURVE_MERCHANT = AccountId(7)
        val EXCHANGE = AccountId(8)
        val WALLET = AccountId(9)
        val EXCHANGE_FEES = AccountId(10)
    }
}

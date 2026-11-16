package com.moneymanager.observationmatcher

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.observation.ImportRecordId
import com.moneymanager.domain.model.observation.ObservationAttribute
import com.moneymanager.domain.model.observation.ObservationContent
import com.moneymanager.domain.model.observation.ObservationEnd
import com.moneymanager.domain.model.observation.ObservationFacts
import com.moneymanager.domain.model.observation.ObservationOwner
import com.moneymanager.domain.model.observation.ObservedAssetId
import com.moneymanager.domain.model.observation.ObservedValues
import com.moneymanager.domain.model.rules.SettlementWindow
import com.moneymanager.importengineapi.AccountBridge
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.Materializer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class CrossSourceRulesTest {
    @Test
    fun `a bank and a broker that share no account are one transfer real end to real end`() {
        val monzo = transfer(1, MONZO, from = MONZO_ACCOUNT, to = FREETRADE_LIMITED, own = ObservationEnd.SOURCE)
        val freetrade =
            transfer(
                2,
                FREETRADE,
                from = TOP_UPS,
                to = ISA,
                hour = 26,
                placeholder = ObservationEnd.SOURCE,
                own = ObservationEnd.TARGET,
            )

        for (order in listOf(listOf(monzo, freetrade), listOf(freetrade, monzo))) {
            val result = matcher().match(order)
            assertSameTransaction(result, monzo, freetrade)
            assertEquals(listOf("other side"), result.joins.map { it.rule })
        }
        val merged = merge(monzo, freetrade)
        assertEquals(MONZO_ACCOUNT, merged.sourceAccountId)
        assertEquals(ISA, merged.targetAccountId)
    }

    @Test
    fun `the named record must own the end where the placeholder stands`() {
        // The bank's statement is Freetrade Limited's, not Monzo's: it can't vouch for where the money came from.
        val monzo = transfer(1, MONZO, from = MONZO_ACCOUNT, to = FREETRADE_LIMITED, own = ObservationEnd.TARGET)
        val freetrade = transfer(2, FREETRADE, from = TOP_UPS, to = ISA, placeholder = ObservationEnd.SOURCE, own = ObservationEnd.TARGET)

        assertSeparate(matcher().match(listOf(monzo, freetrade)), monzo, freetrade)
    }

    @Test
    fun `a statement naming only the holder meets another account of the holder`() {
        val santander =
            transfer(1, SANTANDER, from = HOLDER, to = SANTANDER_ACCOUNT, placeholder = ObservationEnd.SOURCE, own = ObservationEnd.TARGET)
        val marcus = transfer(2, MARCUS, from = MARCUS_ACCOUNT, to = SANTANDER_NUMBER, hour = 20, own = ObservationEnd.SOURCE)

        val result = matcher().match(listOf(santander, marcus))

        assertSameTransaction(result, santander, marcus)
        val merged = merge(santander, marcus)
        assertEquals(MARCUS_ACCOUNT, merged.sourceAccountId)
        assertEquals(SANTANDER_ACCOUNT, merged.targetAccountId)
    }

    @Test
    fun `a placeholder record and one naming the far end through the same account are one in either order`() {
        val card = transfer(1, AMEX, from = PAYMENT_RECEIVED, to = CARD, placeholder = ObservationEnd.SOURCE, own = ObservationEnd.TARGET)
        val bank = transfer(2, SANTANDER, from = SANTANDER_ACCOUNT, to = CARD, hour = 30, own = ObservationEnd.SOURCE)

        for (order in listOf(listOf(card, bank), listOf(bank, card))) {
            val result = matcher().match(order)
            assertSameTransaction(result, card, bank)
            assertEquals(listOf("unidentified counterparty"), result.joins.map { it.rule })
        }
        val merged = merge(card, bank)
        assertEquals(SANTANDER_ACCOUNT, merged.sourceAccountId)
        assertEquals(CARD, merged.targetAccountId)
    }

    @Test
    fun `the named far end may not be an account the source of the placeholder books`() {
        val card = transfer(1, AMEX, from = PAYMENT_RECEIVED, to = CARD, placeholder = ObservationEnd.SOURCE)
        val bank = transfer(2, SANTANDER, from = SANTANDER_ACCOUNT, to = CARD)

        val result = matcher(containerAccounts = mapOf(AMEX to setOf(CARD, SANTANDER_ACCOUNT))).match(listOf(card, bank))

        assertSeparate(result, card, bank)
    }

    @Test
    fun `an approximately dated record takes the time of the precise one`() {
        val precise = transfer(1, EXCHANGE, from = WALLET, to = SHOP, hour = 15)
        val approximate =
            transfer(2, STATEMENT, from = WALLET, to = SHOP, approximate = true, until = START + 24.hours, description = "Shop")

        val result = matcher().match(listOf(approximate, precise))

        assertSameTransaction(result, precise, approximate)
        assertEquals(listOf("approximate twin"), result.joins.map { it.rule })
        assertEquals(START + 15.hours, merge(precise, approximate).timestamp)
    }

    @Test
    fun `an approximate record outside the window stays apart`() {
        val precise = transfer(1, EXCHANGE, from = WALLET, to = SHOP, hour = 100)
        val approximate = transfer(2, STATEMENT, from = WALLET, to = SHOP, approximate = true, until = START + 24.hours)

        assertSeparate(matcher().match(listOf(approximate, precise)), precise, approximate)
    }

    @Test
    fun `the same accounts and amount from two sources within the window are one`() {
        val a = transfer(1, MONZO, from = MONZO_ACCOUNT, to = SANTANDER_ACCOUNT)
        val b = transfer(2, SANTANDER, from = MONZO_ACCOUNT, to = SANTANDER_ACCOUNT, hour = 1)
        val far = transfer(3, SANTANDER, from = MONZO_ACCOUNT, to = SANTANDER_ACCOUNT, hour = 10, container = "santander-2")

        val result = matcher().match(listOf(a, b, far))

        assertSameTransaction(result, a, b)
        assertSeparate(result, a, far)
        assertEquals(listOf("same accounts"), result.joins.map { it.rule })
    }

    @Test
    fun `an internal transfer recorded at both ends runs from the app to the exchange`() {
        val exchange = transfer(1, EXCHANGE, from = OUTSIDE, to = EXCHANGE_ACCOUNT, amount = 1000, own = ObservationEnd.TARGET)
        val app = transfer(2, APP, from = APP_ACCOUNT, to = ADDRESS, amount = 1005, hour = 1, own = ObservationEnd.SOURCE)
        val tooFar =
            transfer(3, APP, from = APP_ACCOUNT, to = ADDRESS, amount = 1100, hour = 1, own = ObservationEnd.SOURCE, container = "a2")

        val result = matcher().match(listOf(app, exchange, tooFar))

        assertSameTransaction(result, exchange, app)
        assertSeparate(result, exchange, tooFar)
        assertEquals(listOf("internal-transfer bridge"), result.joins.map { it.rule })
        val merged = merge(exchange, app)
        assertEquals(APP_ACCOUNT, merged.sourceAccountId)
        assertEquals(EXCHANGE_ACCOUNT, merged.targetAccountId)
    }

    @Test
    fun `fees and shadow and excluded and pass-through records and records of one source are never twins`() {
        val monzo = transfer(1, MONZO, from = MONZO_ACCOUNT, to = FREETRADE_LIMITED, own = ObservationEnd.SOURCE)
        val twin = { record: Long, family: String ->
            transfer(record, family, from = TOP_UPS, to = ISA, placeholder = ObservationEnd.SOURCE, own = ObservationEnd.TARGET)
        }
        val cases =
            listOf(
                twin(2, FREETRADE).copy(ref = ObservationRef(ObservationOwner.Record(ImportRecordId(2)), "fee")),
                twin(3, FREETRADE).copy(crossSource = CrossSourceFacts(shadow = true)),
                twin(4, FREETRADE).let { it.copy(content = it.content.copy(attributes = setOf(EXCLUDED))) },
                twin(5, FREETRADE).copy(crossSource = CrossSourceFacts(passThrough = true)),
                twin(6, MONZO).copy(container = "monzo-2"),
            )

        for (other in cases) {
            assertSeparate(matcher().match(listOf(monzo, other)), monzo, other)
        }
    }

    @Test
    fun `each record is matched once and the nearest wins`() {
        val monzo = transfer(1, MONZO, from = MONZO_ACCOUNT, to = FREETRADE_LIMITED, own = ObservationEnd.SOURCE, hour = 10)
        val far = transfer(2, FREETRADE, from = TOP_UPS, to = ISA, placeholder = ObservationEnd.SOURCE, own = ObservationEnd.TARGET)
        val near =
            transfer(3, FREETRADE, from = TOP_UPS, to = ISA, placeholder = ObservationEnd.SOURCE, own = ObservationEnd.TARGET, hour = 12)

        val result = matcher().match(listOf(far, monzo, near))

        assertSameTransaction(result, monzo, near)
        assertSeparate(result, monzo, far)
    }

    @Test
    fun `permuting the input never changes the result`() {
        val scope =
            listOf(
                transfer(1, MONZO, from = MONZO_ACCOUNT, to = FREETRADE_LIMITED, own = ObservationEnd.SOURCE, hour = 10),
                transfer(2, FREETRADE, from = TOP_UPS, to = ISA, placeholder = ObservationEnd.SOURCE, own = ObservationEnd.TARGET),
                transfer(
                    3,
                    FREETRADE,
                    from = TOP_UPS,
                    to = ISA,
                    placeholder = ObservationEnd.SOURCE,
                    own = ObservationEnd.TARGET,
                    hour = 20,
                ),
                transfer(4, MONZO, from = MONZO_ACCOUNT, to = FREETRADE_LIMITED, own = ObservationEnd.SOURCE, hour = 15, container = "m2"),
                transfer(5, AMEX, from = PAYMENT_RECEIVED, to = CARD, placeholder = ObservationEnd.SOURCE, own = ObservationEnd.TARGET),
                transfer(6, SANTANDER, from = SANTANDER_ACCOUNT, to = CARD, hour = 5, own = ObservationEnd.SOURCE),
                transfer(7, MARCUS, from = SANTANDER_ACCOUNT, to = CARD, hour = 6, own = ObservationEnd.TARGET),
                transfer(8, EXCHANGE, from = WALLET, to = SHOP, hour = 15),
                transfer(9, STATEMENT, from = WALLET, to = SHOP, approximate = true, until = START + 24.hours),
                transfer(10, EXCHANGE, from = OUTSIDE, to = EXCHANGE_ACCOUNT, amount = 1000, own = ObservationEnd.TARGET),
                transfer(11, APP, from = APP_ACCOUNT, to = ADDRESS, amount = 1005, hour = 1, own = ObservationEnd.SOURCE),
                transfer(12, APP, from = APP_ACCOUNT, to = ADDRESS, amount = 995, hour = 1, own = ObservationEnd.SOURCE, container = "a2"),
            )
        val matcher = matcher()
        val expected = matcher.match(scope)

        val orders = scope.indices.map { scope.drop(it) + scope.take(it) } + (1..40).map { scope.shuffled(Random(it)) }
        for (order in orders + orders.map { it.reversed() }) {
            assertEquals(expected, matcher.match(order))
            assertEquals(merged(expected, scope), merged(matcher.match(order), order))
        }
        assertTrue(expected.joins.size >= 5, "${expected.joins}")
    }

    @Test
    fun `the merge prefers precise times and named ends and itemised amounts whatever the order`() {
        val gross =
            ObservationContent(
                values(from = PAYMENT_RECEIVED, to = CARD, amount = 105, hour = 0),
                facts = ObservationFacts(approximateTime = true, placeholderEnds = setOf(ObservationEnd.SOURCE), grossAmount = true),
            )
        val itemised = ObservationContent(values(from = SANTANDER_ACCOUNT, to = CARD, amount = 100, hour = 7, description = ""))

        for (order in listOf(listOf(gross, itemised), listOf(itemised, gross))) {
            val merged = merge(order)
            assertEquals(SANTANDER_ACCOUNT, merged.sourceAccountId)
            assertEquals(CARD, merged.targetAccountId)
            assertEquals(BigInteger(100), merged.amount)
            assertEquals(START + 7.hours, merged.timestamp)
            assertEquals("Payment", merged.description)
        }
    }

    /** Each transaction's merged values, by its members' refs: the same whatever order the scope came in. */
    private fun merged(
        result: MatchResult,
        scope: List<MatchObservation>,
    ): Set<Pair<Set<ObservationRef>, ObservedValues.Transfer>> =
        scope
            .groupBy { result.assignments.getValue(it.ref) }
            .values
            .map { members -> members.map { it.ref }.toSet() to merge(*members.toTypedArray()) }
            .toSet()

    private fun merge(vararg observations: MatchObservation): ObservedValues.Transfer = merge(observations.map { it.content })

    private fun merge(contents: List<ObservationContent>): ObservedValues.Transfer =
        checkNotNull(Materializer.merge(contents)).values as ObservedValues.Transfer

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

    private fun matcher(containerAccounts: Map<String, Set<AccountId>> = emptyMap()): ObservationMatcher =
        ObservationMatcher(SameFamilyRules.forPolicies(POLICIES) + CrossSourceRules.forPolicies(POLICIES, containerAccounts))

    @Suppress("LongParameterList")
    private fun values(
        from: AccountId,
        to: AccountId,
        amount: Long,
        hour: Int,
        description: String = "Payment",
    ) = ObservedValues.Transfer(
        timestamp = START + hour.hours,
        description = description,
        sourceAccountId = from,
        targetAccountId = to,
        assetId = ObservedAssetId(1),
        amount = BigInteger(amount),
    )

    @Suppress("LongParameterList")
    private fun transfer(
        record: Long,
        family: String,
        from: AccountId,
        to: AccountId,
        amount: Long = 500,
        hour: Int = 0,
        placeholder: ObservationEnd? = null,
        own: ObservationEnd? = null,
        approximate: Boolean = false,
        until: Instant? = null,
        description: String = "Payment",
        container: String = family,
    ) = MatchObservation(
        ref = ObservationRef(ObservationOwner.Record(ImportRecordId(record)), "main"),
        storedId = null,
        current = null,
        family = family,
        container = container,
        recordKey = "k-$record",
        content =
            ObservationContent(
                values = values(from, to, amount, hour, description),
                facts = ObservationFacts(approximateTime = approximate, placeholderEnds = setOfNotNull(placeholder), ownEnd = own),
            ),
        crossSource = CrossSourceFacts(approximateUntil = until),
    )

    private companion object {
        val START = Instant.parse("2026-03-02T09:00:00Z")

        const val MONZO = "Monzo"
        const val FREETRADE = "Freetrade"
        const val SANTANDER = "Santander"
        const val MARCUS = "Marcus"
        const val AMEX = "Amex"
        const val EXCHANGE = "Exchange"
        const val STATEMENT = "Statement"
        const val APP = "App"

        val MONZO_ACCOUNT = AccountId(1)
        val FREETRADE_LIMITED = AccountId(2)
        val TOP_UPS = AccountId(3)
        val ISA = AccountId(4)
        val HOLDER = AccountId(5)
        val SANTANDER_ACCOUNT = AccountId(6)
        val MARCUS_ACCOUNT = AccountId(7)
        val SANTANDER_NUMBER = AccountId(8)
        val PAYMENT_RECEIVED = AccountId(9)
        val CARD = AccountId(10)
        val WALLET = AccountId(11)
        val SHOP = AccountId(12)
        val OUTSIDE = AccountId(13)
        val EXCHANGE_ACCOUNT = AccountId(14)
        val APP_ACCOUNT = AccountId(15)
        val ADDRESS = AccountId(16)

        val EXCLUDED = ObservationAttribute(AttributeTypeId(WellKnownIds.EXCLUDED_ATTR_TYPE_ID), "", "declined")

        private val EXCLUSION = AttributeTypeId(-20)
        private val RELATIONSHIP = RelationshipTypeId(1)
        private val UNIDENTIFIED = AttributeTypeId(-21)

        val BANK =
            DedupePolicy.UniqueIdentifier(
                reconcileWindow = SettlementWindow.Elapsed(2 * 3600),
                reconciledExclusionAttributeTypeId = EXCLUSION,
                reconciledRelationshipTypeId = RELATIONSHIP,
                unidentifiedCounterpartyAttributeTypeId = UNIDENTIFIED,
                unidentifiedCounterpartyWindow = SettlementWindow.BusinessDays(3),
            )
        val STATEMENTS =
            DedupePolicy.FuzzyAllFields(
                reconcileWindow = SettlementWindow.Elapsed(2 * 3600),
                reconciledExclusionAttributeTypeId = EXCLUSION,
                reconciledRelationshipTypeId = RELATIONSHIP,
                unidentifiedCounterpartyAttributeTypeId = UNIDENTIFIED,
            )
        val EXCHANGE_POLICY =
            DedupePolicy.ApiMultiKey(
                reconcileWindow = SettlementWindow.Elapsed(2 * 3600),
                reconciledExclusionAttributeTypeId = EXCLUSION,
                reconciledRelationshipTypeId = RELATIONSHIP,
                internalTransferBridges = listOf(AccountBridge(exchangeAccountId = EXCHANGE_ACCOUNT, appAccountId = APP_ACCOUNT)),
                internalTransferWindow = SettlementWindow.Elapsed(6 * 3600),
                internalTransferAmountTolerance = BigDecimal("1"),
                unidentifiedCounterpartyAttributeTypeId = UNIDENTIFIED,
                unidentifiedCounterpartyWindow = SettlementWindow.BusinessDays(3),
            )
        val POLICIES =
            mapOf(
                MONZO to BANK,
                FREETRADE to BANK,
                SANTANDER to BANK,
                MARCUS to BANK,
                AMEX to BANK,
                APP to BANK,
                STATEMENT to STATEMENTS,
                EXCHANGE to EXCHANGE_POLICY,
            )
    }
}

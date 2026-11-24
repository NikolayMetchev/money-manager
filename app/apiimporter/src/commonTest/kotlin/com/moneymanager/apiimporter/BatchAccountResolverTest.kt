package com.moneymanager.apiimporter

import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Source
import com.moneymanager.importengineapi.AccountMatchKey
import com.moneymanager.importengineapi.AccountRef
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BatchAccountResolverTest {
    private val source = Source.Manual

    @Test
    fun `a source account seen twice keeps one intent and gains bank details`() =
        runTest {
            val resolver = BatchAccountResolver()

            val first = resolver.resolveSourceAccount("ext-1", "Current", source)
            val second = resolver.resolveSourceAccount("ext-1", "Current", source, sortCode = "200000", accountNumber = "12345678")

            assertEquals(first, second)
            val attrs = resolver.intents().single().attributes
            assertTrue(attrs.any { it.typeId == ACCOUNT_SORT_CODE_ATTR_TYPE_ID && it.value == "200000" })
            assertTrue(attrs.any { it.typeId == ACCOUNT_ACCOUNT_NUMBER_ATTR_TYPE_ID && it.value == "12345678" })
        }

    @Test
    fun `a source account adopts a counterparty already allocated for the same bank account`() =
        runTest {
            val resolver = BatchAccountResolver()
            val identity = PersonalCounterpartyIdentity("Alice", "200000", "12345678")
            val counterparty =
                resolver.resolveCounterpartyAccount(
                    null,
                    null,
                    "Alice",
                    source,
                    dedupeKey = identity.dedupeKey,
                    personalIdentity = identity,
                )

            val own = resolver.resolveSourceAccount("ext-9", "Alice", source, sortCode = "200000", accountNumber = "12345678")

            assertEquals(counterparty, own)
            assertEquals(1, resolver.intents().size)
            assertTrue(
                resolver
                    .intents()
                    .single()
                    .attributes
                    .any { it.value == "ext-9" },
            )
        }

    @Test
    fun `an attribute is added to a source account by its external id and ignored for an unknown one`() =
        runTest {
            val resolver = BatchAccountResolver()
            resolver.resolveSourceAccount("ext-1", "Current", source)
            val attribute = NewAttribute(typeId = ACCOUNT_SORT_CODE_ATTR_TYPE_ID, value = "111111")

            resolver.addSourceAttribute("ext-1", attribute)
            resolver.addSourceAttribute("nope", attribute)

            assertTrue(
                resolver
                    .intents()
                    .single()
                    .attributes
                    .contains(attribute),
            )
        }

    @Test
    fun `an unreferenced source account is pruned and a counterparty never is`() =
        runTest {
            val resolver = BatchAccountResolver()
            val used = resolver.resolveSourceAccount("a", "Used", source)
            val unused = resolver.resolveSourceAccount("b", "Unused", source)
            val counterparty = resolver.resolveCounterpartyAccount("cp", null, "Shop", source)

            val pruned = resolver.pruneUnreferencedSourceAccounts(setOf(used))

            assertEquals(setOf(unused), pruned)
            assertEquals(setOf(used, counterparty), resolver.intents().map { it.key }.toSet())
            assertEquals(emptySet(), resolver.pruneUnreferencedSourceAccounts(setOf(used)))
        }

    @Test
    fun `a fee account and a named account are one intent each`() =
        runTest {
            val resolver = BatchAccountResolver()
            val own = AccountRef.Local(resolver.resolveSourceAccount("a", "Own", source))

            val fee = resolver.resolveFeeAccount(own, source)
            val named = resolver.resolveNamedAccount("", source)

            assertEquals(fee, resolver.resolveFeeAccount(own, source))
            assertEquals(named, resolver.resolveNamedAccount("Unknown", source))
            assertNotEquals(fee, named)
            assertTrue(resolver.intents().any { it.match is AccountMatchKey.FeeAccountOf })
        }

    @Test
    fun `counterparties collapse by built in type then by name`() =
        runTest {
            val resolver = BatchAccountResolver()

            val atm = resolver.resolveCounterpartyAccount("loc-1", "atm", "ATM A", source)
            assertEquals(atm, resolver.resolveCounterpartyAccount("loc-2", "atm", "ATM B", source))
            val noId = resolver.resolveCounterpartyAccount(null, "cash", "Cash", source)
            assertEquals(noId, resolver.resolveCounterpartyAccount(null, "cash", "Cash", source))

            val business = resolver.resolveCounterpartyAccount(null, null, "The Coffee Shop Ltd", source)
            assertEquals(business, resolver.resolveCounterpartyAccount(null, null, "the coffee shop ltd", source))
            assertNotEquals(business, resolver.resolveCounterpartyAccount(null, null, "The Bike Shop Ltd", source))

            val person = resolver.resolveCounterpartyAccount(null, null, "Nikolay Ivanov Metchev", source, personalCounterparty = true)
            assertEquals(person, resolver.resolveCounterpartyAccount(null, null, "Nikolay Metchev", source, personalCounterparty = true))

            val blank = resolver.resolveCounterpartyAccount(null, null, "", source)
            assertTrue(resolver.intents().first { it.key == blank }.match is AccountMatchKey.ByName)
        }
}

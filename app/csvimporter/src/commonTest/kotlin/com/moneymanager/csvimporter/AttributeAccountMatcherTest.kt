package com.moneymanager.csvimporter

import com.moneymanager.domain.model.AccountAttribute
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.AttributeTypeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AttributeAccountMatcherTest {
    private var nextId = 1L

    private fun attribute(
        accountId: Long,
        value: String,
        typeName: String = "card-last4",
    ): AccountAttribute =
        AccountAttribute(
            id = nextId++,
            accountId = AccountId(accountId),
            attributeType = AttributeType(id = AttributeTypeId(-8), name = typeName),
            value = value,
        )

    @Test
    fun `trivial digit token matches the funding column value`() {
        val matcher = AttributeAccountMatcher.from(listOf(attribute(accountId = 10, value = "7721")))

        assertEquals(AccountId(10), matcher.match("7721"))
        assertNull(matcher.match("1234"))
    }

    @Test
    fun `a real regex pattern matches by containsMatchIn`() {
        val matcher = AttributeAccountMatcher.from(listOf(attribute(accountId = 20, value = "ACME.*LTD")))

        assertEquals(AccountId(20), matcher.match("CARD PAYMENT TO ACME TRADING LTD"))
        assertNull(matcher.match("ACME TRADING"))
    }

    @Test
    fun `multiple whitespace or comma separated tokens each match`() {
        val matcher = AttributeAccountMatcher.from(listOf(attribute(accountId = 30, value = "7721, 1234 9999")))

        assertEquals(AccountId(30), matcher.match("7721"))
        assertEquals(AccountId(30), matcher.match("1234"))
        assertEquals(AccountId(30), matcher.match("9999"))
    }

    @Test
    fun `a value claimed by more than one account is ambiguous and ignored`() {
        val matcher =
            AttributeAccountMatcher.from(
                listOf(
                    attribute(accountId = 40, value = "7721"),
                    attribute(accountId = 41, value = "7721"),
                ),
            )

        assertNull(matcher.match("7721"))
    }

    @Test
    fun `matching is case insensitive`() {
        val matcher = AttributeAccountMatcher.from(listOf(attribute(accountId = 50, value = "paypal")))

        assertEquals(AccountId(50), matcher.match("PayPal Europe"))
    }

    @Test
    fun `registry groups matchers by attribute type name`() {
        val registry =
            AttributeAccountMatcher.registry(
                listOf(
                    attribute(accountId = 60, value = "7721"),
                    attribute(accountId = 61, value = "acme", typeName = "merchant-key"),
                ),
            )

        assertEquals(AccountId(60), registry.getValue("card-last4").match("7721"))
        assertEquals(AccountId(61), registry.getValue("merchant-key").match("ACME LTD"))
        assertNull(registry["card-last4"]?.match("acme"))
    }
}

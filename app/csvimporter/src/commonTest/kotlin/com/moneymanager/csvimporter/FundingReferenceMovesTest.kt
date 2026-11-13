package com.moneymanager.csvimporter

import com.moneymanager.domain.model.AccountAttribute
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class FundingReferenceMovesTest {
    private val type = AttributeType(AttributeTypeId(-8), "card-last4")
    private val old = AccountId(1)
    private val new = AccountId(2)

    private fun repository(vararg values: Pair<AccountId, String>) =
        object : AccountAttributeReadRepository {
            val attributes =
                values.mapIndexed { index, (accountId, value) -> AccountAttribute(index + 1L, accountId, type, value) }

            override fun getByAccount(accountId: AccountId): Flow<List<AccountAttribute>> =
                flowOf(attributes.filter { it.accountId == accountId })

            override fun getByType(typeId: AttributeTypeId): Flow<List<AccountAttribute>> =
                flowOf(attributes.filter { it.attributeType.id == typeId })

            override fun getAll(): Flow<List<AccountAttribute>> = flowOf(attributes)
        }

    private fun reference(
        value: String,
        owner: AccountId?,
        ownedByToken: Boolean = owner != null,
    ) = FundingReference(
        value = value,
        attributeTypeName = type.name,
        rowCount = 1,
        strategyNames = listOf("Curve"),
        files = listOf(FundingReferenceFile(CsvImportId(Uuid.random()), 1, emptyMap())),
        ambiguous = false,
        owner = owner,
        ownedByToken = ownedByToken,
    )

    @Test
    fun movingATokenTakesAlongEveryCardItAlsoMatches() =
        runTest {
            val card = reference("7721", old)
            val prefixed = reference("CARD-7721", old, ownedByToken = false)
            val other = reference("1142", old)

            val moves = planFundingReferenceMoves(mapOf(card to new), listOf(card, prefixed, other), repository(old to "7721 1142"))

            assertEquals(listOf("7721", "CARD-7721"), moves.affected.map { it.value })
        }

    @Test
    fun aMoveThatLeavesAPatternOnTheOldOwnerMatchingTheCardIsRefused() =
        runTest {
            val card = reference("4647", old)

            assertFailsWith<IllegalArgumentException> {
                planFundingReferenceMoves(mapOf(card to new), listOf(card), repository(old to "4647 46.*"))
            }
        }

    @Test
    fun anUnownedCardMovesAloneOntoItsAccount() =
        runTest {
            val card = reference("6002", owner = null)
            val unrelated = reference("1142", old)

            val moves = planFundingReferenceMoves(mapOf(card to new), listOf(card, unrelated), repository(old to "1142"))

            assertEquals(listOf("6002"), moves.affected.map { it.value })
        }

    @Test
    fun aPickOfTheCurrentOwnerPlansNothing() =
        runTest {
            val card = reference("1142", old)

            val moves = planFundingReferenceMoves(mapOf(card to old), listOf(card), repository(old to "1142"))

            assertEquals(emptyList(), moves.affected)
        }
}

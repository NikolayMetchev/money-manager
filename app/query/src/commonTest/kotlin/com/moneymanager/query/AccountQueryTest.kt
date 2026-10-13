package com.moneymanager.query

import com.moneymanager.domain.model.AccountAttribute
import com.moneymanager.domain.model.AccountBalance
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.PersonAccountOwnership
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountQueryTest {
    private val current = account(1, "Current")
    private val wallet = account(2, "Wallet")
    private val koinly = account(3, "Koinly Wallet")

    private val query =
        AccountQuery(
            accountRepository =
                mock<AccountReadRepository> { every { getAllAccounts() } returns flowOf(listOf(current, wallet, koinly)) },
            accountAttributeRepository =
                mock<AccountAttributeReadRepository> {
                    val type =
                        AttributeType(AttributeTypeId(WellKnownIds.ACCOUNT_RECONCILIATION_SOURCE_ATTR_TYPE_ID), "reconciliation-source")
                    every { getByType(any()) } returns
                        flowOf(listOf(AccountAttribute(id = 1, accountId = koinly.id, attributeType = type, value = "Koinly")))
                },
            transactionRepository =
                mock<TransactionReadRepository> {
                    every { getAccountBalances() } returns
                        flowOf(
                            listOf(
                                AccountBalance(current.id, money("10", gbp)),
                                AccountBalance(wallet.id, money("1", btc)),
                                AccountBalance(wallet.id, money("-2", gbp)),
                                AccountBalance(koinly.id, money("1", btc)),
                            ),
                        )
                },
            personRepository = mock<PersonReadRepository> { every { getAllPeople() } returns flowOf(listOf(alice, bob)) },
            personAccountOwnershipRepository =
                mock<PersonAccountOwnershipReadRepository> {
                    every { getAllOwnerships() } returns
                        flowOf(
                            listOf(
                                PersonAccountOwnership(id = 1, personId = alice.id, accountId = current.id),
                                PersonAccountOwnership(id = 2, personId = alice.id, accountId = wallet.id),
                                PersonAccountOwnership(id = 3, personId = bob.id, accountId = wallet.id),
                            ),
                        )
                },
        )

    @Test
    fun snapshotJoinsAccountsOwnersBalancesAndShadowFlag() =
        runTest {
            val snapshot = query.snapshot().first()

            assertEquals(
                listOf(
                    AccountWithBalances(current, listOf(alice), listOf(money("10", gbp)), isShadow = false),
                    AccountWithBalances(wallet, listOf(alice, bob), listOf(money("1", btc), money("-2", gbp)), isShadow = false),
                    AccountWithBalances(koinly, emptyList(), listOf(money("1", btc)), isShadow = true),
                ),
                snapshot.accounts,
            )
            assertEquals(listOf(alice, bob), snapshot.people)
        }

    @Test
    fun accountsWithBalancesAppliesTheCriteria() =
        runTest {
            val result = query.accountsWithBalances(AccountQueryCriteria(assets = setOf(AssetRef.Code("BTC")))).first()

            assertEquals(listOf(AccountWithBalances(wallet, listOf(alice, bob), listOf(money("1", btc)), isShadow = false)), result)
        }
}

package com.moneymanager.database.repository

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.RelationshipMember
import com.moneymanager.domain.model.RelationshipRole
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.importengineapi.ManualEntryInput
import com.moneymanager.importengineapi.createManualEntries
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createAccount
import com.moneymanager.test.database.hasDisplayValue
import com.moneymanager.test.database.upsertCurrencyByCode
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class PendingManualEntriesTest : DbTest() {
    private suspend fun createTransfer(
        transfer: Transfer,
        entryName: String?,
    ): TransferId {
        val attributes =
            listOfNotNull(
                entryName?.let {
                    NewAttribute(repositories.attributeTypeRepository.getOrCreate(WellKnownIds.MANUAL_ENTRY_ATTR_TYPE_NAME), it)
                },
            )
        return repositories.transactionRepository
            .createTransfers(
                transfers = listOf(transfer),
                newAttributes = mapOf(transfer.id to attributes),
                sources = listOf(Source.SampleGenerator),
            ).single()
    }

    private fun transfer(
        timestamp: Instant,
        description: String,
        sourceAccountId: AccountId,
        targetAccountId: AccountId,
        currency: Currency,
        amount: String,
    ) = Transfer(
        id = TransferId(0L),
        timestamp = timestamp,
        description = description,
        sourceAccountId = sourceAccountId,
        targetAccountId = targetAccountId,
        amount = Money.fromDisplayValue(amount, currency),
    )

    @Test
    fun `reports tagged transfers until an entry is linked to them`() =
        runDbTest {
            val now = Clock.System.now()
            val wiseEur = repositories.accountRepository.createAccount(Account(id = AccountId(0), name = "Wise: EUR", openingDate = now))
            val transferwise =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "TransferWise", openingDate = now),
                )
            val currencyId = repositories.currencyRepository.upsertCurrencyByCode("EUR", "Euro")
            val currency = repositories.currencyRepository.getCurrencyById(currencyId).first()!!

            val fee = createTransfer(transfer(now, "Assets fee 1", wiseEur, transferwise, currency, "0.06"), "Interest earned")
            // Tagged for a different entry, and untagged: neither is reported for "Interest earned".
            createTransfer(transfer(now, "Withdrawal", wiseEur, transferwise, currency, "5.00"), "Withdrawal fee")
            createTransfer(transfer(now, "Card payment", wiseEur, transferwise, currency, "10.00"), null)

            val pending =
                repositories.transactionRepository
                    .getPendingManualEntries("Interest earned", "interest")
                    .first()
                    .single()
            assertEquals(fee, pending.transferId)
            assertEquals("Assets fee 1", pending.description)
            assertEquals(now.toEpochMilliseconds(), pending.timestamp.toEpochMilliseconds())
            assertEquals(wiseEur, pending.sourceAccountId)
            assertEquals("Wise: EUR", pending.sourceAccountName)
            assertEquals(transferwise, pending.targetAccountId)
            assertEquals("TransferWise", pending.targetAccountName)
            assertEquals(Money.fromDisplayValue("0.06", currency), pending.amount)

            // An entry linked to a DIFFERENT transfer does not satisfy this one, and neither does another relationship type.
            val entry = createTransfer(transfer(now, "Interest earned", transferwise, wiseEur, currency, "0.42"), null)
            val typeId = repositories.relationshipTypeRepository.getOrCreate("interest")
            val otherTypeId = repositories.relationshipTypeRepository.getOrCreate("other")
            repositories.relationshipRepository.create(
                otherTypeId,
                listOf(RelationshipMember(fee, RelationshipRole.PRIMARY), RelationshipMember(entry, RelationshipRole.RELATED)),
                Source.Manual,
            )
            assertEquals(
                1,
                repositories.transactionRepository
                    .getPendingManualEntries("Interest earned", "interest")
                    .first()
                    .size,
            )

            // Linked with the rule's relationship type and the transfer as the primary, it is no longer pending.
            repositories.relationshipRepository.create(
                typeId,
                listOf(RelationshipMember(fee, RelationshipRole.PRIMARY), RelationshipMember(entry, RelationshipRole.RELATED)),
                Source.Manual,
            )
            assertTrue(
                repositories.transactionRepository
                    .getPendingManualEntries("Interest earned", "interest")
                    .first()
                    .isEmpty(),
            )
        }

    @Test
    fun `two transfers with one entry name are paired independently`() =
        runDbTest {
            val now = Clock.System.now()
            val binance = repositories.accountRepository.createAccount(Account(id = AccountId(0), name = "Binance", openingDate = now))
            val funding =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Binance Funding", openingDate = now),
                )
            val currencyId = repositories.currencyRepository.upsertCurrencyByCode("EUR", "Euro")
            val currency = repositories.currencyRepository.getCurrencyById(currencyId).first()!!

            val first = createTransfer(transfer(now, "Withdraw", binance, funding, currency, "5.00"), "Withdrawal fee")
            val second = createTransfer(transfer(now, "Withdraw", binance, funding, currency, "7.00"), "Withdrawal fee")
            val entry = createTransfer(transfer(now, "Withdrawal fee", binance, funding, currency, "0.10"), null)
            repositories.relationshipRepository.create(
                repositories.relationshipTypeRepository.getOrCreate("fee"),
                listOf(RelationshipMember(first, RelationshipRole.PRIMARY), RelationshipMember(entry, RelationshipRole.RELATED)),
                Source.Manual,
            )

            val pending = repositories.transactionRepository.getPendingManualEntries("Withdrawal fee", "fee").first()
            assertEquals(listOf(second), pending.map { it.transferId })
        }

    @Test
    fun `a fee entry the original already contains is linked and excluded and counted once`() =
        runDbTest {
            val now = Clock.System.now()
            val binance = repositories.accountRepository.createAccount(Account(id = AccountId(0), name = "Binance", openingDate = now))
            val funding =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Binance Funding", openingDate = now),
                )
            val currencyId = repositories.currencyRepository.upsertCurrencyByCode("EUR", "Euro")
            val currency = repositories.currencyRepository.getCurrencyById(currencyId).first()!!
            createTransfer(transfer(now, "Withdraw", binance, funding, currency, "5.00"), "Withdrawal fee")
            val original =
                repositories.transactionRepository
                    .getPendingManualEntries("Withdrawal fee", "fee")
                    .first()
                    .single()

            repositories.importEngine.createManualEntries(
                inputs = listOf(ManualEntryInput(original, Money.fromDisplayValue("0.10", currency))),
                description = "Withdrawal fee",
                relationshipType = "fee",
                counterpartyAccount = "Binance Fees",
                excludedFromBalances = true,
            )
            repositories.maintenanceService.refreshMaterializedViews()

            assertTrue(
                repositories.transactionRepository
                    .getPendingManualEntries("Withdrawal fee", "fee")
                    .first()
                    .isEmpty(),
            )
            val fee =
                repositories.transactionRepository
                    .getTransactionsByAccount(binance)
                    .first()
                    .single { it.description == "Withdrawal fee" }
            assertEquals(binance, fee.sourceAccountId)
            assertTrue(fee.amount.hasDisplayValue("0.10"))
            assertTrue(fee.attributes.any { it.attributeType.id.id == WellKnownIds.EXCLUDED_ATTR_TYPE_ID })
            // The withdrawal already contains the fee, so Binance is down by the gross amount only.
            val balances = repositories.transactionRepository.getAccountBalances().first()
            assertEquals(
                "-5",
                balances
                    .first { it.accountId == binance }
                    .balance
                    .toDisplayValue()
                    .toString(),
            )
        }
}

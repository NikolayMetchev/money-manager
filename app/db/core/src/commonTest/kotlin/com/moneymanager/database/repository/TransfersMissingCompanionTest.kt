package com.moneymanager.database.repository
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createAccount
import com.moneymanager.test.database.upsertCurrencyByCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class TransfersMissingCompanionTest : DbTest() {
    private suspend fun createAttributedTransfer(
        transfer: Transfer,
        attributes: Map<String, String>,
    ): TransferId {
        val newAttributes =
            attributes.map { (name, value) ->
                NewAttribute(repositories.attributeTypeRepository.getOrCreate(name), value)
            }
        return repositories.transactionRepository
            .createTransfers(
                transfers = listOf(transfer),
                newAttributes = mapOf(transfer.id to newAttributes),
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
    fun `reports matched transfers until their companion is created`() =
        runTest {
            val now = Clock.System.now()
            val wiseEur =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Wise: EUR", openingDate = now),
                )
            val transferwise =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "TransferWise", openingDate = now),
                )
            val currencyId = repositories.currencyRepository.upsertCurrencyByCode("EUR", "Euro")
            val currency = repositories.currencyRepository.getCurrencyById(currencyId).first()!!

            // An assets fee that needs an interest companion
            createAttributedTransfer(
                transfer(now, "Assets fee 1", wiseEur, transferwise, currency, "0.06"),
                attributes = mapOf("wise-id" to "ACCRUAL_CHARGE-1"),
            )
            // A transfer whose wise-id doesn't match the pattern is never reported
            createAttributedTransfer(
                transfer(now, "Card payment", wiseEur, transferwise, currency, "10.00"),
                attributes = mapOf("wise-id" to "BALANCE-2"),
            )

            val missing =
                repositories.transactionRepository
                    .getTransfersMissingCompanion("wise-id", "ACCRUAL_CHARGE-%", "wise-interest-for")
                    .first()

            val pending = missing.single()
            assertEquals("ACCRUAL_CHARGE-1", pending.matchValue)
            assertEquals("Assets fee 1", pending.description)
            assertEquals(now.toEpochMilliseconds(), pending.timestamp.toEpochMilliseconds())
            assertEquals(wiseEur, pending.sourceAccountId)
            assertEquals("Wise: EUR", pending.sourceAccountName)
            assertEquals(transferwise, pending.targetAccountId)
            assertEquals("TransferWise", pending.targetAccountName)
            assertEquals(Money.fromDisplayValue("0.06", currency), pending.amount)

            // A link attribute for a DIFFERENT fee does not satisfy this one
            createAttributedTransfer(
                transfer(now, "Interest earned (other)", transferwise, wiseEur, currency, "0.10"),
                attributes = mapOf("wise-interest-for" to "ACCRUAL_CHARGE-99"),
            )
            assertEquals(
                1,
                repositories.transactionRepository
                    .getTransfersMissingCompanion("wise-id", "ACCRUAL_CHARGE-%", "wise-interest-for")
                    .first()
                    .size,
            )

            // The mirror companion linked to this fee clears it from the list
            createAttributedTransfer(
                transfer(now, "Interest earned", transferwise, wiseEur, currency, "0.42"),
                attributes = mapOf("wise-interest-for" to "ACCRUAL_CHARGE-1"),
            )
            assertTrue(
                repositories.transactionRepository
                    .getTransfersMissingCompanion("wise-id", "ACCRUAL_CHARGE-%", "wise-interest-for")
                    .first()
                    .isEmpty(),
            )
        }

    @Test
    fun `attribute names are matched exactly`() =
        runTest {
            val now = Clock.System.now()
            val wiseEur =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Wise: EUR", openingDate = now),
                )
            val transferwise =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "TransferWise", openingDate = now),
                )
            val currencyId = repositories.currencyRepository.upsertCurrencyByCode("EUR", "Euro")
            val currency = repositories.currencyRepository.getCurrencyById(currencyId).first()!!

            createAttributedTransfer(
                transfer(now, "Assets fee 1", wiseEur, transferwise, currency, "0.06"),
                attributes = mapOf("wise-id" to "ACCRUAL_CHARGE-1"),
            )

            // A different match attribute name finds nothing
            assertTrue(
                repositories.transactionRepository
                    .getTransfersMissingCompanion("monzo-id", "ACCRUAL_CHARGE-%", "wise-interest-for")
                    .first()
                    .isEmpty(),
            )
            // A non-matching pattern finds nothing
            assertTrue(
                repositories.transactionRepository
                    .getTransfersMissingCompanion("wise-id", "CARD_TRANSACTION-%", "wise-interest-for")
                    .first()
                    .isEmpty(),
            )
        }
}

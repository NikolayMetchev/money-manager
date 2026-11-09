package com.moneymanager.database.repository

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.importengineapi.MANUAL_FEE_DESCRIPTION
import com.moneymanager.importengineapi.createManualFee
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createAccount
import com.moneymanager.test.database.upsertCurrencyByCode
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Clock

class ManualFeeTest : DbTest() {
    private val now = Clock.System.now()

    private suspend fun account(name: String): AccountId =
        repositories.accountRepository.createAccount(Account(id = AccountId(0), name = name, openingDate = now))

    private suspend fun euro(): Currency {
        val currencyId = repositories.currencyRepository.upsertCurrencyByCode("EUR", "Euro")
        return repositories.currencyRepository.getCurrencyById(currencyId).first()!!
    }

    private suspend fun feeAccountOf(fee: TransferId): Account {
        val transfer = repositories.transactionRepository.getTransactionById(fee.id).first()!!
        return repositories.accountRepository.getAccountById(transfer.targetAccountId).first()!!
    }

    private suspend fun feeAccountForValue(account: AccountId): String? =
        repositories.accountAttributeRepository
            .getByAccount(account)
            .first()
            .singleOrNull { it.attributeType.id.id == WellKnownIds.ACCOUNT_FEE_ACCOUNT_FOR_ATTR_TYPE_ID }
            ?.value

    @Test
    fun `books the fee into the accounts own fee account and reuses it`() =
        runDbTest {
            val binance = account("Binance")
            val currency = euro()

            val first = repositories.importEngine.createManualFee(binance, Money.fromDisplayValue("0.30", currency), now)
            val second = repositories.importEngine.createManualFee(binance, Money.fromDisplayValue("0.10", currency), now)
            assertNotEquals(first, second)

            val fees =
                repositories.transactionRepository
                    .getTransactionsByAccount(binance)
                    .first()
                    .filter { it.description == MANUAL_FEE_DESCRIPTION }
            assertEquals(
                setOf(Money.fromDisplayValue("0.30", currency), Money.fromDisplayValue("0.10", currency)),
                fees.map { it.amount }.toSet(),
            )
            val feeAccount = feeAccountOf(first)
            assertEquals("Binance Fees", feeAccount.name)
            assertEquals(feeAccount.id, feeAccountOf(second).id)
            assertEquals(binance.id.toString(), feeAccountForValue(feeAccount.id))
        }

    @Test
    fun `an untagged account named after the account is not its fee account`() =
        runDbTest {
            val revolutX = account("Revolut X")
            val namesake = account("Revolut X Fees")

            val feeAccount = feeAccountOf(repositories.importEngine.createManualFee(revolutX, Money.fromDisplayValue("1", euro()), now))

            assertNotEquals(namesake, feeAccount.id)
            assertEquals(revolutX.id.toString(), feeAccountForValue(feeAccount.id))
            assertNull(feeAccountForValue(namesake))
        }

    @Test
    fun `finds the fee account by its tag after it is renamed`() =
        runDbTest {
            val kraken = account("Kraken")
            val currency = euro()
            val feeAccount = feeAccountOf(repositories.importEngine.createManualFee(kraken, Money.fromDisplayValue("1", currency), now))
            repositories.accountRepository.updateAccount(feeAccount.copy(name = "Exchange charges"), Source.Manual)
            // An untagged account with the default name must not take over from the tagged one.
            account("Kraken Fees")

            val again = repositories.importEngine.createManualFee(kraken, Money.fromDisplayValue("2", currency), now)

            assertEquals(feeAccount.id, feeAccountOf(again).id)
        }

    @Test
    fun `gives each account its own fee account`() =
        runDbTest {
            val currency = euro()
            val gbp = account("Wise GBP")
            val eur = account("Wise EUR")

            val gbpFee = feeAccountOf(repositories.importEngine.createManualFee(gbp, Money.fromDisplayValue("1", currency), now))
            val eurFee = feeAccountOf(repositories.importEngine.createManualFee(eur, Money.fromDisplayValue("1", currency), now))

            assertEquals("Wise GBP Fees", gbpFee.name)
            assertEquals("Wise EUR Fees", eurFee.name)
        }

    @Test
    fun `the fee account of a shadow account belongs to the same reconciliation source`() =
        runDbTest {
            val wallet = account("Koinly · Binance")
            val shadowType = AttributeTypeId(WellKnownIds.ACCOUNT_RECONCILIATION_SOURCE_ATTR_TYPE_ID)
            repositories.accountAttributeRepository.insertInCreationMode(wallet, shadowType, "Koinly")

            val fee = repositories.importEngine.createManualFee(wallet, Money.fromDisplayValue("0.30", euro()), now)

            val feeAccount = feeAccountOf(fee)
            assertEquals(
                "Koinly",
                repositories.accountAttributeRepository
                    .getByAccount(feeAccount.id)
                    .first()
                    .single { it.attributeType.id == shadowType }
                    .value,
            )
        }

    @Test
    fun `refuses a fee that is not positive`() =
        runDbTest {
            val binance = account("Binance")
            val currency = euro()

            assertFailsWith<IllegalArgumentException> {
                repositories.importEngine.createManualFee(binance, Money.fromDisplayValue("0", currency), now)
            }
        }
}

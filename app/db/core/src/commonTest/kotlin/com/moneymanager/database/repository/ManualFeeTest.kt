package com.moneymanager.database.repository

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Money
import com.moneymanager.importengineapi.MANUAL_FEE_ACCOUNT_NAME
import com.moneymanager.importengineapi.MANUAL_FEE_DESCRIPTION
import com.moneymanager.importengineapi.createManualFee
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createAccount
import com.moneymanager.test.database.upsertCurrencyByCode
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock

class ManualFeeTest : DbTest() {
    @Test
    fun `books the fee from the account to a Fees account and reuses that account`() =
        runDbTest {
            val now = Clock.System.now()
            val binance = repositories.accountRepository.createAccount(Account(id = AccountId(0), name = "Binance", openingDate = now))
            val currencyId = repositories.currencyRepository.upsertCurrencyByCode("EUR", "Euro")
            val currency = repositories.currencyRepository.getCurrencyById(currencyId).first()!!

            val first = repositories.importEngine.createManualFee(binance, Money.fromDisplayValue("0.30", currency), now)
            val second = repositories.importEngine.createManualFee(binance, Money.fromDisplayValue("0.10", currency), now)
            assertEquals(false, first == second)

            val fees =
                repositories.transactionRepository
                    .getTransactionsByAccount(binance)
                    .first()
                    .filter { it.description == MANUAL_FEE_DESCRIPTION }
            assertEquals(
                listOf(Money.fromDisplayValue("0.30", currency), Money.fromDisplayValue("0.10", currency)).toSet(),
                fees
                    .map {
                        it.amount
                    }.toSet(),
            )
            assertEquals(
                1,
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .count { it.name == MANUAL_FEE_ACCOUNT_NAME },
            )
        }

    @Test
    fun `refuses a fee that is not positive`() =
        runDbTest {
            val now = Clock.System.now()
            val binance = repositories.accountRepository.createAccount(Account(id = AccountId(0), name = "Binance", openingDate = now))
            val currencyId = repositories.currencyRepository.upsertCurrencyByCode("EUR", "Euro")
            val currency = repositories.currencyRepository.getCurrencyById(currencyId).first()!!

            assertFailsWith<IllegalArgumentException> {
                repositories.importEngine.createManualFee(binance, Money.fromDisplayValue("0", currency), now)
            }
        }
}

@file:OptIn(ExperimentalTime::class)

package com.moneymanager.database.audit

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.Money
import com.moneymanager.importengineapi.createCrypto
import com.moneymanager.importengineapi.createTrade
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createAccount
import com.moneymanager.test.database.upsertCurrencyByCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Trades write their audit trail through DB triggers like transfers do; the audit history read
 * must surface the audited field values so the UI can render a full "Created with" card.
 */
class TradeAuditHistoryTest : DbTest() {
    @Test
    fun `engine-created trade has an INSERT audit entry carrying its field values`() =
        runTest {
            val now = Clock.System.now()
            val usdId = repositories.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
            val usd = repositories.currencyRepository.getCurrencyById(usdId).first()!!
            val btcId = repositories.importEngine.createCrypto("BTC")
            val btc = repositories.cryptoRepository.getCryptoAssetByCode("BTC").first()!!
            check(btcId.id == btc.id.id)
            repositories.accountRepository.createAccount(
                Account(id = AccountId(0), name = "Exchange", openingDate = now),
            )
            val exchange =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .first { it.name == "Exchange" }

            val fromAmount = Money.fromDisplayValue("100.00", usd)
            val toAmount = Money.fromDisplayValue("0.001", btc)
            val tradeId =
                repositories.importEngine.createTrade(
                    timestamp = now,
                    description = "Sell USD/BTC",
                    fromAccountId = exchange.id,
                    fromAmount = fromAmount,
                    toAccountId = exchange.id,
                    toAmount = toAmount,
                )

            val entries = repositories.auditRepository.getAuditHistoryForTrade(tradeId)

            val insert = entries.single()
            assertEquals(AuditType.INSERT, insert.auditType)
            assertEquals(tradeId, insert.tradeId)
            assertEquals("Sell USD/BTC", insert.description)
            assertEquals(exchange.id, insert.fromAccountId)
            assertEquals(exchange.id, insert.toAccountId)
            assertEquals(fromAmount, insert.fromAmount)
            assertEquals(toAmount, insert.toAmount)
            assertNotNull(insert.source)
        }
}

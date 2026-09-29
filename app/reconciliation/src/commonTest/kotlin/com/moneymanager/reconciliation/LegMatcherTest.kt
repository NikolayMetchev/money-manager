package com.moneymanager.reconciliation

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.reconciliation.LegTransactionKind
import com.moneymanager.domain.model.reconciliation.ReconciliationLeg
import com.moneymanager.domain.model.reconciliation.ReconciliationLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class LegMatcherTest {
    private val ada = CryptoAsset(id = CryptoId(1), code = "ADA", name = "Cardano")
    private val spam = CryptoAsset(id = CryptoId(2), code = "CLAIM-USDT.INFO", name = "spam")

    private val wallet = AccountId(100) // shadow "Koinly · Binance"
    private val sourceRewards = AccountId(101) // shadow "Koinly: reward"
    private val binance = AccountId(1)
    private val binanceEarn = AccountId(2)
    private val stakingRewards = AccountId(3)
    private val links = listOf(ReconciliationLink(wallet, binance), ReconciliationLink(wallet, binanceEarn))

    private val t0 = Instant.parse("2021-05-15T00:56:32Z")
    private var nextId = 1L

    private fun leg(
        account: AccountId,
        counterparty: AccountId?,
        amount: String,
        at: Instant,
        asset: CryptoAsset = ada,
        excluded: Boolean = false,
    ) = ReconciliationLeg(
        transactionId = nextId++,
        kind = if (counterparty == null) LegTransactionKind.TRADE else LegTransactionKind.TRANSFER,
        timestamp = at,
        description = "",
        accountId = account,
        counterpartyAccountId = counterparty,
        amount = Money.fromDisplayValue(BigDecimal(amount), asset),
        isExcluded = excluded,
    )

    private fun ada(amount: String) = Money.fromDisplayValue(BigDecimal(amount), ada)

    @Test
    fun `exact second matches and unmatched legs land on the right side`() {
        val source = listOf(leg(wallet, sourceRewards, "0.00051544", t0), leg(wallet, sourceRewards, "1", t0 + 1.hours))
        val real = listOf(leg(binance, stakingRewards, "0.00051544", t0), leg(binance, stakingRewards, "2", t0 + 30.minutes))

        val result = reconcile(source, links, real)

        assertEquals(1, result.exactMatches.size)
        assertEquals(listOf(ada("1")), result.missingInMm.map { it.leg.amount })
        assertEquals(listOf(ada("2")), result.missingInSource.map { it.leg.amount })
    }

    @Test
    fun `fuzzy pass pairs the nearest leg within the window and never reuses one`() {
        val source = listOf(leg(wallet, sourceRewards, "5", t0), leg(wallet, sourceRewards, "5", t0 + 1.hours))
        val real = listOf(leg(binance, stakingRewards, "5", t0 + 2.hours), leg(binance, stakingRewards, "5", t0 + 30.hours))

        val result = reconcile(source, links, real)

        assertEquals(1, result.fuzzyMatches.size)
        assertEquals(
            t0 + 1.hours,
            result.fuzzyMatches
                .single()
                .sourceLeg.timestamp,
        )
        assertEquals(1, result.missingInMm.size)
        // Outside the source's date range, so out of scope rather than missing from the source.
        assertTrue(result.missingInSource.isEmpty())
    }

    @Test
    fun `identical movements pair one to one`() {
        val source = List(3) { leg(wallet, sourceRewards, "1", t0) }
        val real = List(2) { leg(binance, stakingRewards, "1", t0) }

        val result = reconcile(source, links, real)

        assertEquals(2, result.exactMatches.size)
        assertEquals(1, result.missingInMm.size)
    }

    @Test
    fun `moves between accounts linked to one wallet are internal and ignored`() {
        val source = listOf(leg(wallet, sourceRewards, "1", t0))
        val real =
            listOf(
                leg(binance, binanceEarn, "-7", t0),
                leg(binanceEarn, binance, "7", t0),
                leg(binance, stakingRewards, "1", t0),
            )

        val result = reconcile(source, links, real)

        assertEquals(1, result.exactMatches.size)
        assertTrue(result.missingInSource.isEmpty())
    }

    @Test
    fun `excluded real legs and legs on unlinked shadow accounts are out of scope`() {
        val unlinkedWallet = AccountId(102)
        val source = listOf(leg(wallet, sourceRewards, "1", t0), leg(unlinkedWallet, sourceRewards, "3", t0))
        val real = listOf(leg(binance, stakingRewards, "1", t0, excluded = true))

        val result = reconcile(source, links, real)

        assertEquals(listOf(wallet), result.missingInMm.map { it.walletAccountId })
        assertTrue(result.missingInSource.isEmpty())
    }

    @Test
    fun `assets never seen on the linked accounts are flagged`() {
        val source = listOf(leg(wallet, sourceRewards, "1", t0), leg(wallet, sourceRewards, "1000", t0, asset = spam))
        val real = listOf(leg(binance, stakingRewards, "2", t0))

        val flags = reconcile(source, links, real).missingInMm.associate { it.leg.amount.asset.code to it.assetUnknownToMm }

        assertEquals(mapOf("ADA" to false, "CLAIM-USDT.INFO" to true), flags)
    }

    @Test
    fun `a fee booked separately on one side matches the other side's gross leg`() {
        // Source: trade principal + its fee, same second. Real: one gross debit half a minute later.
        val source = listOf(leg(wallet, null, "-985.32", t0), leg(wallet, sourceRewards, "-14.68", t0))
        val real = listOf(leg(binance, null, "-1000", t0 + 35.seconds))

        val result = reconcile(source, links, real)

        assertEquals(1, result.matches.size)
        assertEquals(
            2,
            result.matches
                .single()
                .sourceLegs.size,
        )
        assertTrue(result.missingInMm.isEmpty())
        assertTrue(result.missingInSource.isEmpty())
    }
}

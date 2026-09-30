package com.moneymanager.reconciliation

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.reconciliation.ReconciliationLink
import com.moneymanager.domain.model.reconciliation.ReconciliationSource
import com.moneymanager.domain.model.reconciliation.ShadowAccount
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportProgress
import com.moneymanager.importengineapi.ImportResult
import com.moneymanager.importengineapi.ReconciliationLinkMutation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class AutoLinkerTest {
    private val source = ReconciliationSource("Koinly", listOf("Koinly"), linkableAccountPrefix = "Koinly · ")
    private val epoch = Instant.fromEpochMilliseconds(0)

    private fun real(
        id: Long,
        name: String,
    ) = Account(id = AccountId(id), name = name, openingDate = epoch)

    private fun shadow(
        id: Long,
        name: String,
    ) = ShadowAccount(AccountId(id), name, "Koinly")

    private val reals =
        listOf(
            real(1, "Binance"),
            real(2, "Binance Earn"),
            real(3, "Crypto.com"),
            real(4, "coinbase"),
            real(5, "OF"),
        )

    @Test
    fun `wallets whose name matches exactly one real account are linked`() {
        val shadows =
            listOf(
                shadow(100, "Koinly · Binance"),
                shadow(101, "Koinly · Coinbase"),
                shadow(102, "Koinly · Crypto.com App"),
                // A counterparty, not a wallet: never linked.
                shadow(103, "Koinly: reward"),
            )

        val plan = planAutoLinks(source, shadows, emptyList(), reals)

        assertEquals(mapOf(AccountId(100) to AccountId(1), AccountId(101) to AccountId(4)), plan)
    }

    @Test
    fun `already linked wallets and already claimed accounts are left alone`() {
        val shadows = listOf(shadow(100, "Koinly · Binance"), shadow(101, "Koinly · Coinbase"))
        val links = listOf(ReconciliationLink(AccountId(100), AccountId(4)))

        val plan = planAutoLinks(source, shadows, links, reals)

        // Coinbase is taken by the Binance wallet's (manual) link, so it can't be auto-linked again.
        assertEquals(emptyMap(), plan)
    }

    @Test
    fun `two wallets resolving to one account are ambiguous`() {
        val shadows = listOf(shadow(100, "Koinly · Binance"), shadow(101, "Koinly · BINANCE"))

        assertEquals(emptyMap(), planAutoLinks(source, shadows, emptyList(), reals))
    }

    @Test
    fun `unlinked wallets get a best-guess suggestion`() {
        val shadows = listOf(shadow(102, "Koinly · Crypto.com App"), shadow(103, "Koinly: reward"), shadow(104, "Koinly · Optimism (OP)"))

        val unlinked = unlinkedWallets(source, shadows, emptyList(), reals)

        assertEquals(listOf(AccountId(102), AccountId(104)), unlinked.map { it.wallet.accountId })
        assertEquals("Crypto.com", unlinked[0].suggestion?.name)
        assertNull(unlinked[1].suggestion)
    }

    @Test
    fun `suggestion prefers the shortest account extending the wallet name`() {
        assertEquals("Binance Earn", suggestRealAccount("Bin", listOf(real(2, "Binance Earn"), real(6, "Binance Earn Rewards")))?.name)
    }

    @Test
    fun `a wallet whose links the user removed is never linked automatically`() {
        val shadows = listOf(ShadowAccount(AccountId(100), "Koinly · Binance", "Koinly", autoLinkDeclined = true))

        assertEquals(emptyMap(), planAutoLinks(source, shadows, emptyList(), reals))
    }

    @Test
    fun `accounts are planned for wallets whose name is free`() {
        val shadows =
            listOf(
                shadow(100, "Koinly · Ledger"),
                // An account already has this name (ignoring case): link to it instead.
                shadow(101, "Koinly · BINANCE"),
                // Two wallets would create the same account: the user decides.
                shadow(102, "Koinly · Trezor"),
                shadow(103, "Koinly · trezor"),
                shadow(104, "Koinly · "),
            )

        val plan = planAccountCreation(source, shadows, reals)

        assertEquals(mapOf(AccountId(100) to "Ledger"), plan)
    }

    @Test
    fun `created accounts are linked to their wallets`() =
        runTest {
            val batches = mutableListOf<ImportBatch>()
            val engine =
                object : ImportEngine {
                    override suspend fun import(
                        batch: ImportBatch,
                        onProgress: (suspend (ImportProgress) -> Unit)?,
                        batchSize: Int,
                    ): ImportResult {
                        batches += batch
                        return ImportResult(
                            createdAccountIds = batch.accountsToCreate.withIndex().associate { (i, it) -> it.key to AccountId(500L + i) },
                        )
                    }
                }

            engine.createAndLinkAccounts(mapOf(AccountId(100) to "Ledger", AccountId(101) to "Trezor"), epoch)

            assertEquals(listOf("Ledger", "Trezor"), batches[0].accountsToCreate.map { it.name })
            assertEquals(
                listOf(
                    ReconciliationLinkMutation.SetLinks(AccountId(100), setOf(AccountId(500))),
                    ReconciliationLinkMutation.SetLinks(AccountId(101), setOf(AccountId(501))),
                ),
                batches[1].reconciliationLinkMutations,
            )
        }
}

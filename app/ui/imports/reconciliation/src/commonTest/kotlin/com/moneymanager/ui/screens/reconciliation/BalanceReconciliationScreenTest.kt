@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.screens.reconciliation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.SourceRecord
import com.moneymanager.domain.model.reconciliation.LegTransactionKind
import com.moneymanager.domain.model.reconciliation.ReconciliationLeg
import com.moneymanager.domain.model.reconciliation.ReportedBalance
import com.moneymanager.domain.model.reconciliation.ReportedBalanceId
import com.moneymanager.domain.model.reconciliation.ReportedBalanceSeries
import com.moneymanager.domain.repository.ReconciliationReadRepository
import com.moneymanager.domain.repository.ReportedBalanceReadRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.presenter.error.ProvideSchemaAwareScope
import com.moneymanager.ui.foundation.LocalImportEngine
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

class BalanceReconciliationScreenTest {
    private val coin = CryptoAsset(id = CryptoId(1), code = "GBX", name = "Test pound")
    private val account = AccountId(7)
    private val t0 = Instant.parse("2025-01-01T09:00:00Z")
    private val file = CsvImportId(Uuid.random())

    private fun money(amount: String) = Money.fromDisplayValue(BigDecimal(amount), coin)

    private fun balance(
        id: Long,
        amount: String,
        day: Int,
    ) = ReportedBalance(ReportedBalanceId(id), t0 + day.days, money(amount), revisionId = 1)

    private fun leg(
        id: Long,
        amount: String,
        day: Int,
        description: String,
    ) = ReconciliationLeg(
        transactionId = id,
        kind = LegTransactionKind.TRANSFER,
        timestamp = t0 + day.days,
        description = description,
        accountId = account,
        counterpartyAccountId = AccountId(8),
        amount = money(amount),
        excludedReason = null,
    )

    // The statement says the balance fell by 30 on day 1; Money Manager booked that payment twice.
    private val legs = listOf(leg(1, "100", 0, "Top up"), leg(2, "-30", 1, "Coffee"), leg(3, "-30", 1, "Coffee again"))
    private val reported = listOf(balance(10, "100", 0), balance(11, "70", 1))

    private fun runScreen(
        onOpenLeg: (ReconciliationLeg) -> Unit = {},
        onOpenSource: (Source) -> Unit = {},
        assertions: ComposeUiTest.() -> Unit,
    ) {
        val balances: ReportedBalanceReadRepository =
            mock {
                every { getSeries() } returns flowOf(listOf(ReportedBalanceSeries(account, "Revolut", coin, 2, t0 + 1.days)))
                everySuspend { getBalances(any(), any()) } returns reported
                everySuspend { getSources(any()) } returns
                    listOf(
                        SourceRecord(
                            id = 1,
                            entityType = EntityType.REPORTED_BALANCE,
                            entityId = 11,
                            revisionId = 1,
                            source = Source.Csv(file, rowIndex = 2),
                            deviceId = 1,
                            deviceInfo = null,
                            fileName = "statement.csv",
                            createdAt = t0,
                        ),
                    )
            }
        val reconciliation: ReconciliationReadRepository = mock { everySuspend { getLegs(any()) } returns legs }
        val engine: ImportEngine = mock { every { writeVersion } returns MutableStateFlow(0L) }
        runMoneyManagerComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalImportEngine provides engine) {
                    ProvideSchemaAwareScope {
                        BalanceReconciliationScreen(
                            reportedBalanceRepository = balances,
                            reconciliationRepository = reconciliation,
                            onOpenLeg = onOpenLeg,
                            onOpenSource = onOpenSource,
                        )
                    }
                }
            }
            assertions()
        }
    }

    @Test
    fun aDuplicatedTransactionShowsAsOneBreakWithItsSuspects() {
        val opened = mutableListOf<Long>()
        runScreen(onOpenLeg = { opened += it.transactionId }) {
            waitUntilAtLeastOneExists(hasTestTag("balance-break"))
            assertEquals(1, onAllNodesWithTag("balance-break").fetchSemanticsNodes().size)
            onNodeWithText("Show 2 transactions", substring = true).performClick()
            waitUntilAtLeastOneExists(hasText("Coffee again", substring = true))
            onNodeWithText("Coffee again", substring = true).performClick()
            waitForIdle()
        }
        assertEquals(listOf(3L), opened)
    }

    @Test
    fun aStatementBalanceOpensTheRowThatReportedIt() {
        val opened = mutableListOf<Source>()
        runScreen(onOpenSource = { opened += it }) {
            waitUntilAtLeastOneExists(hasTestTag("balance-break"))
            onNodeWithText("Show 2 transactions", substring = true).performClick()
            waitUntilAtLeastOneExists(hasText("open its record", substring = true))
            onNodeWithText("open its record", substring = true).performClick()
            waitUntil { opened.isNotEmpty() }
        }
        assertEquals(listOf<Source>(Source.Csv(file, rowIndex = 2)), opened)
    }
}

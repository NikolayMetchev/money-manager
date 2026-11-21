package com.moneymanager.ui.screens.reconciliation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.reconciliation.ReconciliationLeg
import com.moneymanager.domain.model.reconciliation.ReportedBalance
import com.moneymanager.domain.model.reconciliation.ReportedBalanceSeries
import com.moneymanager.domain.repository.ReconciliationReadRepository
import com.moneymanager.domain.repository.ReportedBalanceReadRepository
import com.moneymanager.presenter.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.presenter.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.reconciliation.BalanceBreak
import com.moneymanager.reconciliation.BalanceCheckResult
import com.moneymanager.reconciliation.checkBalances
import com.moneymanager.ui.foundation.LocalImportEngine
import com.moneymanager.ui.foundation.rememberPersistedState
import com.moneymanager.ui.util.formatAmount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer

/** The key a series is remembered and cached by: its account and asset. */
private val ReportedBalanceSeries.key: String get() = "${accountId.id}:${asset.id.id}"

/**
 * Balance checks kept above the screen, so coming back from a followed link shows them again instead of reading every
 * leg a second time. A check stays good until the database is written to (`ImportEngine.writeVersion`).
 */
class BalanceCheckCache {
    private val entries = mutableMapOf<String, Pair<Long, BalanceCheckResult>>()

    internal fun get(
        key: String,
        writeVersion: Long,
    ): BalanceCheckResult? = entries[key]?.takeIf { it.first == writeVersion }?.second

    internal fun put(
        key: String,
        writeVersion: Long,
        result: BalanceCheckResult,
    ) {
        entries[key] = writeVersion to result
    }
}

/**
 * The balances statements report (a strategy's `Balance` rule action) against the running balance Money Manager
 * computes from the account's legs, one account and asset at a time. Where the two stop differing by the same amount,
 * something between that statement balance and the one before broke the reconciliation: its legs are listed, each
 * opening the account's transactions on it, and each statement balance opens the file row or API item that reported it.
 */
@Composable
fun BalanceReconciliationScreen(
    reportedBalanceRepository: ReportedBalanceReadRepository,
    reconciliationRepository: ReconciliationReadRepository,
    onOpenLeg: (ReconciliationLeg) -> Unit,
    onOpenSource: (Source) -> Unit,
    cache: BalanceCheckCache = remember { BalanceCheckCache() },
) {
    val series by rememberFlowAsStateWithSchemaErrorHandling(initial = null) { reportedBalanceRepository.getSeries() }
    val loaded = series ?: return
    if (loaded.isEmpty()) {
        Text(
            "No statement balances yet. A strategy reports them with a \"Statement balance\" rule (Revolut and Marcus " +
                "do); import a statement it covers on the Files tab.",
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    var selectedKey by
        rememberPersistedState(
            "reconciliation.balances.series",
            String.serializer(),
            restore = { key -> if (loaded.any { it.key == key }) key else loaded.first().key },
        ) { loaded.first().key }
    val selected = loaded.firstOrNull { it.key == selectedKey } ?: loaded.first()

    val importEngine = LocalImportEngine.current
    val writeVersion by importEngine.writeVersion.collectAsState()
    var result by remember { mutableStateOf(cache.get(selected.key, writeVersion)) }
    LaunchedEffect(selected, writeVersion) {
        cache.get(selected.key, writeVersion)?.let {
            result = it
            return@LaunchedEffect
        }
        result = null
        val reported = reportedBalanceRepository.getBalances(selected.accountId, selected.asset)
        val legs = reconciliationRepository.getLegs(setOf(selected.accountId))
        val computed = withContext(Dispatchers.Default) { checkBalances(selected.asset, reported, legs) }
        cache.put(selected.key, writeVersion, computed)
        result = computed
    }

    Row(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.width(280.dp).fillMaxHeight().padding(8.dp)) {
            items(loaded, key = { it.key }) { entry ->
                val chosen = entry.key == selected.key
                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                vertical = 4.dp,
                            ).clickable { selectedKey = entry.key }
                            .testTag("balance-series"),
                    colors =
                        if (chosen) {
                            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        } else {
                            CardDefaults.cardColors()
                        },
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(entry.accountName, fontWeight = FontWeight.Bold)
                        Text("${entry.asset.code} · ${entry.count} statement balances", style = MaterialTheme.typography.bodySmall)
                        cache.get(entry.key, writeVersion)?.let { check ->
                            Text(
                                if (check.breaks.isEmpty()) "✓ Reconciles" else "${check.breaks.size} breaks",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (check.breaks.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
        val shown = result
        if (shown == null) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Checking ${selected.accountName}…")
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        } else {
            BalanceCheckDetail(selected, shown, reportedBalanceRepository, onOpenLeg, onOpenSource)
        }
    }
}

@Composable
private fun BalanceCheckDetail(
    series: ReportedBalanceSeries,
    result: BalanceCheckResult,
    reportedBalanceRepository: ReportedBalanceReadRepository,
    onOpenLeg: (ReconciliationLeg) -> Unit,
    onOpenSource: (Source) -> Unit,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val expanded = remember(series.key) { mutableStateMapOf<Int, Boolean>() }

    fun openBalance(balance: ReportedBalance) {
        scope.launch {
            reportedBalanceRepository.getSources(balance.id).firstOrNull()?.let { onOpenSource(it.source) }
        }
    }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column {
                Text("${series.accountName} · ${series.asset.code}", style = MaterialTheme.typography.titleMedium)
                result.latest?.let { latest ->
                    Text("Latest statement balance ${formatAmount(latest.reportedBalance)} on ${latest.timestamp}")
                    Text("Money Manager has ${formatAmount(latest.computed)}")
                }
                result.openingOffset?.let { offset ->
                    if (!offset.isZero()) {
                        Text(
                            "Opening balance before the first transaction Money Manager has: ${formatAmount(offset)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Text(
                    if (result.breaks.isEmpty()) {
                        "Every statement balance agrees with Money Manager's."
                    } else {
                        "${result.breaks.size} places where the balance stops agreeing:"
                    },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        result.breaks.forEachIndexed { index, balanceBreak ->
            item(key = "break-$index") {
                BreakCard(
                    balanceBreak = balanceBreak,
                    expanded = expanded[index] == true,
                    onToggle = { expanded[index] = expanded[index] != true },
                    onOpenLeg = onOpenLeg,
                    onOpenBalance = ::openBalance,
                )
            }
        }
    }
}

@Composable
private fun BreakCard(
    balanceBreak: BalanceBreak,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenLeg: (ReconciliationLeg) -> Unit,
    onOpenBalance: (ReportedBalance) -> Unit,
) {
    val checkpoint = balanceBreak.checkpoint
    Card(modifier = Modifier.fillMaxWidth().testTag("balance-break")) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${checkpoint.timestamp}", fontWeight = FontWeight.Bold)
                Text("off by ${formatAmount(balanceBreak.change)}", color = MaterialTheme.colorScheme.error)
            }
            Text(
                "Statement ${formatAmount(checkpoint.reportedBalance)} · Money Manager ${formatAmount(checkpoint.computed)}",
                style = MaterialTheme.typography.bodySmall,
            )
            balanceBreak.since?.let { Text("Since the statement balance of $it", style = MaterialTheme.typography.bodySmall) }
            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                checkpoint.reported.forEach { balance ->
                    TextButton(
                        onClick = { onOpenBalance(balance) },
                    ) { Text("Statement balance ${formatAmount(balance.amount)}: open its record") }
                }
                if (balanceBreak.suspects.isEmpty()) {
                    Text(
                        "Money Manager has no transactions in this window: one the statement has is missing.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text("Money Manager's transactions in this window:", style = MaterialTheme.typography.bodySmall)
                    balanceBreak.suspects.forEach { leg ->
                        TextButton(onClick = { onOpenLeg(leg) }, modifier = Modifier.testTag("balance-suspect")) {
                            Text("${leg.timestamp} · ${leg.description.ifBlank { "(no description)" }} · ${formatAmount(leg.amount)}")
                        }
                    }
                }
            } else {
                TextButton(onClick = onToggle) { Text("Show ${balanceBreak.suspects.size} transactions and the statement record") }
            }
        }
    }
}

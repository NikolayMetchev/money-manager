package com.moneymanager.ui.screens.reconciliation

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.AccountId
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

private const val RECONCILED_NODE = "reconciled"
private const val NOT_RECONCILED_NODE = "not-reconciled"

private val BalanceCheckResult.isReconciled: Boolean
    get() = breaks.isEmpty() && latest?.difference?.isZero() == true

/**
 * The balances statements report (a strategy's `Balance` rule action) against the running balance Money Manager
 * computes from the account's legs, one account and asset at a time. Where the two stop differing by the same amount,
 * something between that statement balance and the one before broke the reconciliation: its legs are listed, each
 * opening the account's transactions on it, and each statement balance opens the file row or API item that reported it.
 *
 * The list is a tree: reconciled or not at the top, then account, then asset. Reconciled is collapsed by default.
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
            "No reported balances yet. Import a statement or API session with a strategy that reports balances.",
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
    // Every series is checked, not just the selected one: whether a series is reconciled decides where it is listed.
    val checks = remember { mutableStateMapOf<String, BalanceCheckResult>() }
    LaunchedEffect(loaded, writeVersion) {
        for (entry in loaded) {
            val check =
                cache.get(entry.key, writeVersion) ?: run {
                    val reported = reportedBalanceRepository.getBalances(entry.accountId, entry.asset)
                    val legs = reconciliationRepository.getLegs(setOf(entry.accountId))
                    val balanceTimes = reportedBalanceRepository.getTransactionBalanceTimes(entry.accountId, entry.asset)
                    withContext(Dispatchers.Default) { checkBalances(entry.asset, reported, legs, balanceTimes) }
                        .also { cache.put(entry.key, writeVersion, it) }
                }
            checks[entry.key] = check
        }
    }

    // A series not checked yet counts as not reconciled until its check lands.
    val (reconciled, notReconciled) = loaded.partition { checks[it.key]?.isReconciled == true }
    val tree = BalanceTree(mutableStateMapOf(), checks, selected.key) { selectedKey = it }

    Row(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.width(320.dp).fillMaxHeight().padding(8.dp)) {
            balanceGroup(tree, NOT_RECONCILED_NODE, "Not reconciled", notReconciled, defaultOpen = true)
            balanceGroup(tree, RECONCILED_NODE, "Reconciled", reconciled, defaultOpen = false)
        }
        val shown = checks[selected.key]
        if (shown == null) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Checking ${selected.accountName} ${selected.asset.code}…")
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        } else {
            BalanceCheckDetail(selected, shown, reportedBalanceRepository, onOpenLeg, onOpenSource)
        }
    }
}

/** Open or closed state of each tree branch, and the checks and selection the leaves show. */
private class BalanceTree(
    val expanded: MutableMap<String, Boolean>,
    val checks: Map<String, BalanceCheckResult>,
    val selectedKey: String,
    val onSelect: (String) -> Unit,
) {
    fun isOpen(
        id: String,
        defaultOpen: Boolean,
    ): Boolean = expanded[id] ?: defaultOpen

    fun toggle(
        id: String,
        defaultOpen: Boolean,
    ) {
        expanded[id] = !isOpen(id, defaultOpen)
    }
}

/** One top-level branch of the tree (reconciled or not), with its accounts and their assets below it. */
private fun LazyListScope.balanceGroup(
    tree: BalanceTree,
    id: String,
    title: String,
    series: List<ReportedBalanceSeries>,
    defaultOpen: Boolean,
) {
    val open = tree.isOpen(id, defaultOpen)
    item(key = id) {
        TreeHeader(
            label = "$title (${series.size})",
            depth = 0,
            open = open,
            tag = "tree-node-$id",
            onClick = { tree.toggle(id, defaultOpen) },
        )
    }
    if (!open) return
    series.groupBy { it.accountId }.forEach { (accountId, assets) ->
        val accountNode = "$id/account-${accountId.id}"
        val accountOpen = tree.isOpen(accountNode, defaultOpen = true)
        item(key = accountNode) {
            TreeHeader(
                label = assets.first().accountName,
                depth = 1,
                open = accountOpen,
                tag = "tree-node-$accountNode",
                onClick = { tree.toggle(accountNode, defaultOpen = true) },
            )
        }
        if (accountOpen) {
            items(assets, key = { "$accountNode/${it.key}" }) { entry ->
                AssetLeaf(
                    entry = entry,
                    check = tree.checks[entry.key],
                    chosen = entry.key == tree.selectedKey,
                    onClick = { tree.onSelect(entry.key) },
                )
            }
        }
    }
}

@Composable
private fun TreeHeader(
    label: String,
    depth: Int,
    open: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = (depth * 16).dp, top = 6.dp, bottom = 6.dp)
                .testTag(tag),
    ) {
        Text(if (open) "▾" else "▸", modifier = Modifier.width(20.dp))
        Text(label, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AssetLeaf(
    entry: ReportedBalanceSeries,
    check: BalanceCheckResult?,
    chosen: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(if (chosen) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(start = 36.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
                .testTag("balance-series"),
    ) {
        Text(entry.asset.code, fontWeight = FontWeight.SemiBold)
        Text("${entry.count} statement balances", style = MaterialTheme.typography.bodySmall)
        if (check != null) {
            Text(
                when {
                    check.isReconciled -> "✓ Reconciles"
                    check.breaks.isEmpty() -> "Balance differs"
                    else -> "${check.breaks.size} breaks"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (check.isReconciled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
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
                    if (!latest.difference.isZero()) {
                        Text("Reported minus Money Manager: ${formatAmount(latest.difference)}")
                        TextButton(onClick = { openBalance(latest.reported.last { it.amount == latest.reportedBalance }) }) {
                            Text("View latest balance source")
                        }
                    }
                }
                result.openingOffset?.let { offset ->
                    if (!offset.isZero() && result.checkpoints.size > 1) {
                        Text(
                            "Difference at the first reported balance: ${formatAmount(offset)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                val summary =
                    when {
                        result.breaks.isNotEmpty() -> "${result.breaks.size} changes in the balance difference:"
                        result.isReconciled -> "Every statement balance agrees with Money Manager's."
                        result.checkpoints.size > 1 -> "The balance difference is consistent across the reported balances."
                        else -> null
                    }
                summary?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
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

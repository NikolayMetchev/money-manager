package com.moneymanager.ui.screens.reconciliation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.reconciliation.ReconciliationLeg
import com.moneymanager.domain.model.reconciliation.ReconciliationLink
import com.moneymanager.domain.model.reconciliation.ReconciliationSource
import com.moneymanager.domain.model.reconciliation.ShadowAccount
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.ReconciliationReadRepository
import com.moneymanager.importengineapi.setReconciliationLinks
import com.moneymanager.reconciliation.LegMatch
import com.moneymanager.reconciliation.ReconciliationResult
import com.moneymanager.reconciliation.UnlinkedWallet
import com.moneymanager.reconciliation.applyAutoLinks
import com.moneymanager.reconciliation.planAutoLinks
import com.moneymanager.reconciliation.realAccounts
import com.moneymanager.reconciliation.reconcile
import com.moneymanager.reconciliation.unlinkedWallets
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.foundation.LocalImportEngine
import com.moneymanager.ui.util.displayDateTime
import com.moneymanager.ui.util.formatAmount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration

private enum class ResultTab(
    val label: (String) -> String,
) {
    MISSING_IN_MM({ "Import into Money Manager" }),
    MISSING_IN_SOURCE({ source -> "Import into $source" }),
    FUZZY({ "Near matches" }),
    MATCHED({ "Matched" }),
}

/**
 * Compares each reconciliation source (a CSV strategy with a `ReconciliationConfig`, e.g. Koinly)
 * against the real accounts its wallets are linked to. Wallets whose name matches a real account are
 * linked automatically; the rest are listed first as something to fix — link to an existing account or
 * create one. Below that, the unmatched movements on each side, grouped by wallet and month, tell the
 * user what to import where.
 */
@Composable
fun ReconciliationScreen(
    reconciliationRepository: ReconciliationReadRepository,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    personRepository: PersonReadRepository,
    onOpenLeg: (ReconciliationLeg) -> Unit,
) {
    val sources by rememberFlowAsStateWithSchemaErrorHandling(initial = null) { reconciliationRepository.getSources() }
    val loadedSources = sources
    if (loadedSources == null) {
        CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        return
    }
    if (loadedSources.isEmpty()) {
        Text(
            "No reconciliation sources yet. Install a reconciliation strategy (e.g. Koinly) from the strategy " +
                "catalog, then import its export on the CSV tab.",
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    var selectedName by remember { mutableStateOf(loadedSources.first().name) }
    val source = loadedSources.firstOrNull { it.name == selectedName } ?: loadedSources.first()

    Column(modifier = Modifier.fillMaxSize()) {
        if (loadedSources.size > 1) {
            FlowRow(modifier = Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                loadedSources.forEach {
                    FilterChip(selected = it.name == source.name, onClick = { selectedName = it.name }, label = { Text(it.name) })
                }
            }
        }
        SourceReconciliation(
            source = source,
            reconciliationRepository = reconciliationRepository,
            accountRepository = accountRepository,
            categoryRepository = categoryRepository,
            personRepository = personRepository,
            onOpenLeg = onOpenLeg,
        )
    }
}

@Composable
private fun SourceReconciliation(
    source: ReconciliationSource,
    reconciliationRepository: ReconciliationReadRepository,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    personRepository: PersonReadRepository,
    onOpenLeg: (ReconciliationLeg) -> Unit,
) {
    val importEngine = LocalImportEngine.current
    val scope = rememberSchemaAwareCoroutineScope()
    val allShadowAccounts by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        reconciliationRepository.getShadowAccounts()
    }
    val allLinks by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) { reconciliationRepository.getLinks() }
    val accounts by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) { accountRepository.getAllAccounts() }

    val shadowAccounts = remember(allShadowAccounts, source) { allShadowAccounts.filter { it.sourceName == source.name } }
    val shadowIds = remember(shadowAccounts) { shadowAccounts.mapTo(mutableSetOf()) { it.accountId } }
    val links = remember(allLinks, shadowIds) { allLinks.filter { it.shadowAccountId in shadowIds } }
    val real = remember(accounts, allShadowAccounts) { realAccounts(accounts, allShadowAccounts) }
    val accountNames = remember(accounts) { accounts.associate { it.id to it.name } }
    val allShadowIds = remember(allShadowAccounts) { allShadowAccounts.mapTo(mutableSetOf()) { it.accountId } }
    var error by remember { mutableStateOf<String?>(null) }

    // Link whatever can be linked with certainty as soon as new wallets or real accounts appear.
    LaunchedEffect(source, shadowAccounts, links, real) {
        val plan = planAutoLinks(source, shadowAccounts, links, real)
        if (plan.isNotEmpty()) {
            runCatching { importEngine.applyAutoLinks(plan) }.onFailure { error = "Automatic linking failed: ${it.message}" }
        }
    }

    var refreshKey by remember { mutableStateOf(0) }
    var result by remember { mutableStateOf<ReconciliationResult?>(null) }
    LaunchedEffect(source, shadowIds, links, refreshKey) {
        result = null
        val sourceLegs = reconciliationRepository.getLegs(shadowIds)
        val realLegs = reconciliationRepository.getLegs(links.map { it.realAccountId }.toSet())
        result = withContext(Dispatchers.Default) { reconcile(sourceLegs, links, realLegs) }
    }

    val unlinked = remember(source, shadowAccounts, links, real) { unlinkedWallets(source, shadowAccounts, links, real) }
    var hideUnknownAssets by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(ResultTab.MISSING_IN_MM) }
    val expanded = remember(source) { mutableStateMapOf<String, Boolean>() }

    fun setLinks(
        shadow: AccountId,
        realIds: Set<AccountId>,
    ) {
        scope.launch {
            runCatching { importEngine.setReconciliationLinks(shadow, realIds) }
                .onSuccess { error = null }
                .onFailure { error = it.message }
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { SummaryHeader(source, result, hideUnknownAssets, onRefresh = { refreshKey++ }) }
        error?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 4.dp)) }
        }
        // Wallets the user deliberately unlinked aren't a problem to fix, just not compared.
        val (declined, needsAttention) = unlinked.partition { it.wallet.autoLinkDeclined }
        listOf(
            needsAttention to "Needs attention: ${needsAttention.size} ${source.name} wallet(s) not linked to an account",
            declined to "Not compared: ${declined.size} wallet(s) whose links you removed",
        ).forEach { (wallets, heading) ->
            if (wallets.isEmpty()) return@forEach
            item {
                Text(
                    heading,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (wallets === needsAttention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            items(wallets, key = { "unlinked-${it.wallet.accountId.id}" }) { wallet ->
                UnlinkedWalletCard(
                    wallet = wallet,
                    walletName = source.walletName(wallet.wallet),
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    personRepository = personRepository,
                    isReal = { it.id !in allShadowIds },
                    onLink = { realId -> setLinks(wallet.wallet.accountId, setOf(realId)) },
                )
            }
        }
        item {
            LinkedWallets(
                source = source,
                shadowAccounts = shadowAccounts,
                links = links,
                accountNames = accountNames,
                counts = result?.countsByWallet(hideUnknownAssets).orEmpty(),
                accountRepository = accountRepository,
                categoryRepository = categoryRepository,
                personRepository = personRepository,
                isReal = { it.id !in allShadowIds },
                onSetLinks = ::setLinks,
            )
        }
        val loaded = result
        if (loaded == null) {
            item { CircularProgressIndicator(modifier = Modifier.padding(16.dp)) }
            return@LazyColumn
        }
        item {
            SecondaryTabRow(selectedTabIndex = tab.ordinal, modifier = Modifier.padding(top = 12.dp)) {
                ResultTab.entries.forEach { entry ->
                    Tab(selected = tab == entry, onClick = { tab = entry }, text = { Text(entry.label(source.name)) })
                }
            }
        }
        val walletName: (AccountId) -> String = { id ->
            shadowAccounts.firstOrNull { it.accountId == id }?.let(source::walletName) ?: accountNames[id].orEmpty()
        }
        when (tab) {
            ResultTab.MISSING_IN_MM -> {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = hideUnknownAssets, onCheckedChange = { hideUnknownAssets = it })
                        Text("Hide assets Money Manager has never seen on the linked accounts (e.g. spam airdrops)")
                    }
                }
                val legs = loaded.missingInMm.filter { !hideUnknownAssets || !it.assetUnknownToMm }
                legGroups(
                    prefix = "mm",
                    groups = groupByWalletAndMonth(legs, { it.walletAccountId }, { it.leg.timestamp }, walletName),
                    expanded = expanded,
                    empty = "Everything ${source.name} has on the linked wallets is in Money Manager.",
                ) { LegRow(it.leg, accountNames, onOpenLeg) }
            }
            ResultTab.MISSING_IN_SOURCE ->
                legGroups(
                    prefix = "src",
                    groups = groupByWalletAndMonth(loaded.missingInSource, { it.walletAccountId }, { it.leg.timestamp }, walletName),
                    expanded = expanded,
                    empty = "Everything on the linked accounts in ${source.name}'s date range is in ${source.name}.",
                ) { LegRow(it.leg, accountNames, onOpenLeg) }
            ResultTab.FUZZY ->
                legGroups(
                    prefix = "fuzzy",
                    groups =
                        groupByWalletAndMonth(loaded.fuzzyMatches, { it.sourceLeg.accountId }, { it.sourceLeg.timestamp }, walletName),
                    expanded = expanded,
                    empty = "No near matches: every match agrees to the second.",
                ) { MatchRow(it, accountNames, onOpenLeg) }
            ResultTab.MATCHED ->
                legGroups(
                    prefix = "matched",
                    groups =
                        groupByWalletAndMonth(loaded.exactMatches, { it.sourceLeg.accountId }, { it.sourceLeg.timestamp }, walletName),
                    expanded = expanded,
                    empty = "Nothing matched yet — link the wallets above.",
                ) { MatchRow(it, accountNames, onOpenLeg) }
        }
    }
}

@Composable
private fun SummaryHeader(
    source: ReconciliationSource,
    result: ReconciliationResult?,
    hideUnknownAssets: Boolean,
    onRefresh: () -> Unit,
) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(source.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onRefresh) { Text("Refresh") }
        }
        Text(
            "Strategies: ${source.strategyNames.joinToString()}" +
                (result?.sourceRange?.let { " · data from ${it.start.displayDateTime()} to ${it.endInclusive.displayDateTime()}" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (result != null) {
            val missingInMm = result.missingInMm.count { !hideUnknownAssets || !it.assetUnknownToMm }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                AssistChip(onClick = {}, label = { Text("Matched: ${result.exactMatches.size}") })
                AssistChip(onClick = {}, label = { Text("Near matches: ${result.fuzzyMatches.size}") })
                AssistChip(onClick = {}, label = { Text("Import into Money Manager: $missingInMm") })
                AssistChip(onClick = {}, label = { Text("Import into ${source.name}: ${result.missingInSource.size}") })
            }
        }
    }
}

@Composable
private fun UnlinkedWalletCard(
    wallet: UnlinkedWallet,
    walletName: String,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    personRepository: PersonReadRepository,
    isReal: (Account) -> Boolean,
    onLink: (AccountId) -> Unit,
) {
    val declined = wallet.wallet.autoLinkDeclined
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = if (declined) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer,
            ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(walletName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                if (declined) {
                    "You removed this wallet's links, so it isn't compared or linked automatically. Link it again to compare it."
                } else {
                    "Pick the Money Manager account this wallet mirrors, or create it (\"Create New Account\" in the list)."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            wallet.suggestion?.let { suggestion ->
                TextButton(onClick = { onLink(suggestion.id) }) { Text("Link to suggested account \"${suggestion.name}\"") }
            }
            AccountPicker(
                selectedAccountId = null,
                onAccountSelected = onLink,
                label = "Link to account",
                accountRepository = accountRepository,
                categoryRepository = categoryRepository,
                personRepository = personRepository,
                accountFilter = isReal,
            )
        }
    }
}

@Composable
private fun LinkedWallets(
    source: ReconciliationSource,
    shadowAccounts: List<ShadowAccount>,
    links: List<ReconciliationLink>,
    accountNames: Map<AccountId, String>,
    counts: Map<AccountId, WalletCounts>,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    personRepository: PersonReadRepository,
    isReal: (Account) -> Boolean,
    onSetLinks: (AccountId, Set<AccountId>) -> Unit,
) {
    val linksByWallet = links.groupBy({ it.shadowAccountId }, { it.realAccountId })
    val linked = shadowAccounts.filter { it.accountId in linksByWallet }.sortedBy { it.name.lowercase() }
    if (linked.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { open = !open }) {
            Text(
                "${if (open) "▾" else "▸"} Linked wallets (${linked.size})",
                style = MaterialTheme.typography.titleSmall,
            )
        }
        if (!open) return@Column
        linked.forEach { wallet ->
            val realIds = linksByWallet[wallet.accountId].orEmpty().toSet()
            val count = counts[wallet.accountId]
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                Text(
                    source.walletName(wallet) +
                        (
                            count?.let {
                                " — ${it.missingInMm} to import into Money Manager, ${it.missingInSource} into ${source.name}"
                            } ?: ""
                        ),
                    fontWeight = FontWeight.Medium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    realIds.forEach { realId ->
                        InputChip(
                            selected = false,
                            onClick = { onSetLinks(wallet.accountId, realIds - realId) },
                            label = { Text("${accountNames[realId] ?: realId}  ✕") },
                        )
                    }
                }
                Row(modifier = Modifier.width(360.dp)) {
                    AccountPicker(
                        selectedAccountId = null,
                        onAccountSelected = { onSetLinks(wallet.accountId, realIds + it) },
                        label = "Also compare with…",
                        accountRepository = accountRepository,
                        categoryRepository = categoryRepository,
                        personRepository = personRepository,
                        accountFilter = { isReal(it) && it.id !in realIds },
                    )
                }
            }
        }
    }
}

private fun <T> LazyListScope.legGroups(
    prefix: String,
    groups: List<WalletGroup<T>>,
    expanded: MutableMap<String, Boolean>,
    empty: String,
    row: @Composable (T) -> Unit,
) {
    if (groups.isEmpty()) {
        item { Text(empty, modifier = Modifier.padding(vertical = 12.dp)) }
        return
    }
    groups.forEach { group ->
        val walletKey = "$prefix-${group.walletAccountId.id}"
        item(key = walletKey) {
            Text(
                "${if (expanded[walletKey] == true) "▾" else "▸"} ${group.walletName} (${group.count})",
                style = MaterialTheme.typography.titleSmall,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { expanded[walletKey] = expanded[walletKey] != true }
                        .padding(vertical = 8.dp),
            )
        }
        if (expanded[walletKey] != true) return@forEach
        group.months.forEach { month ->
            val monthKey = "$walletKey-${month.month}"
            item(key = monthKey) {
                Text(
                    "${if (expanded[monthKey] == true) "▾" else "▸"} ${month.month} (${month.items.size})",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { expanded[monthKey] = expanded[monthKey] != true }
                            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                )
            }
            if (expanded[monthKey] == true) {
                items(month.items) { Row(modifier = Modifier.padding(start = 32.dp)) { row(it) } }
            }
        }
    }
}

@Composable
private fun LegRow(
    leg: ReconciliationLeg,
    accountNames: Map<AccountId, String>,
    onOpenLeg: (ReconciliationLeg) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onOpenLeg(leg) }.padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(leg.timestamp.displayDateTime(), modifier = Modifier.width(140.dp), style = MaterialTheme.typography.bodySmall)
        Text(formatAmount(leg.amount), modifier = Modifier.width(180.dp), style = MaterialTheme.typography.bodySmall)
        Text(
            leg.description + (leg.counterpartyAccountId?.let { "  ·  ${accountNames[it].orEmpty()}" } ?: "  ·  trade"),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun MatchRow(
    match: LegMatch,
    accountNames: Map<AccountId, String>,
    onOpenLeg: (ReconciliationLeg) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        match.sourceLegs.forEach { LegRow(it, accountNames, onOpenLeg) }
        match.realLegs.forEach { real ->
            Row {
                Text("↳ ", style = MaterialTheme.typography.bodySmall)
                LegRow(real, accountNames, onOpenLeg)
            }
        }
        if (!match.exact) {
            Text(
                "Δt ${formatDelta(match.timeDelta)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

private fun formatDelta(delta: Duration): String = delta.absoluteValue.toString().let { if (delta.isNegative()) "-$it" else "+$it" }

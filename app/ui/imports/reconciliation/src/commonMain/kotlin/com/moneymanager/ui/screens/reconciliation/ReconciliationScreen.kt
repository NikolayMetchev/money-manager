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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.RelationshipId
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.reconciliation.IGNORED_IN_RECONCILIATION
import com.moneymanager.domain.model.reconciliation.ReconciliationLeg
import com.moneymanager.domain.model.reconciliation.ReconciliationLink
import com.moneymanager.domain.model.reconciliation.ReconciliationSource
import com.moneymanager.domain.model.reconciliation.ShadowAccount
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.ReconciliationReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.importengineapi.createManualFee
import com.moneymanager.importengineapi.createReconciliationMatch
import com.moneymanager.importengineapi.createReconciliationMatches
import com.moneymanager.importengineapi.deleteRelationship
import com.moneymanager.importengineapi.setReconciliationLinks
import com.moneymanager.importengineapi.setTradeExcluded
import com.moneymanager.importengineapi.setTransferExcluded
import com.moneymanager.presenter.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.presenter.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.reconciliation.LegMatch
import com.moneymanager.reconciliation.ReconciliationResult
import com.moneymanager.reconciliation.UnlinkedWallet
import com.moneymanager.reconciliation.applyAutoLinks
import com.moneymanager.reconciliation.copyToMoneyManager
import com.moneymanager.reconciliation.createAndLinkAccounts
import com.moneymanager.reconciliation.planAccountCreation
import com.moneymanager.reconciliation.planAutoLinks
import com.moneymanager.reconciliation.planCopy
import com.moneymanager.reconciliation.realAccounts
import com.moneymanager.reconciliation.reconcile
import com.moneymanager.reconciliation.unlinkedWallets
import com.moneymanager.tasks.Progress
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.components.CreateAccountDialog
import com.moneymanager.ui.foundation.LocalImportEngine
import com.moneymanager.ui.tasks.LocalTaskManager
import com.moneymanager.ui.util.displayDateTime
import com.moneymanager.ui.util.formatAmount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration

/** Rows a bulk run writes per engine call: one transaction each, with the progress bar moving between them. */
private const val BULK_CHUNK = 50

private enum class ResultTab(
    val label: String,
) {
    UNMATCHED("Unmatched"),
    MATCHED("Matched"),
}

/**
 * Compares each reconciliation source (a CSV strategy with a `ReconciliationConfig`, e.g. Koinly)
 * against the real accounts its wallets are linked to. Wallets whose name matches a real account are
 * linked automatically; the rest are listed first as something to fix — link to an existing account or
 * create one. Below that, two tabs. **Unmatched**: near matches to accept, the source's movements Money Manager
 * lacks (to ignore or match by hand to real transactions picked from around that time), and real movements the
 * source lacks. **Matched**: exact matches, the matches a person stored (to unmatch), and ignored source rows (to bring
 * back). Every list is grouped by wallet and month.
 */
@Composable
fun ReconciliationScreen(
    reconciliationRepository: ReconciliationReadRepository,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    personRepository: PersonReadRepository,
    transactionRepository: TransactionReadRepository,
    onOpenLeg: (ReconciliationLeg) -> Unit,
    onOpenSource: (ReconciliationLeg) -> Unit = {},
    pickerState: MatchPickerState = remember { MatchPickerState() },
    cache: ReconciliationCache = remember { ReconciliationCache() },
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
                "catalog, then import its export on the Files tab.",
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
            transactionRepository = transactionRepository,
            onOpenLeg = onOpenLeg,
            onOpenSource = onOpenSource,
            pickerState = pickerState,
            cache = cache,
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
    transactionRepository: TransactionReadRepository,
    onOpenLeg: (ReconciliationLeg) -> Unit,
    onOpenSource: (ReconciliationLeg) -> Unit,
    pickerState: MatchPickerState,
    cache: ReconciliationCache,
) {
    val importEngine = LocalImportEngine.current
    val writeVersion by importEngine.writeVersion.collectAsState()
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
    val existingNames = remember(accounts) { accounts.mapTo(mutableSetOf()) { it.name } }
    var error by remember { mutableStateOf<String?>(null) }

    // Link whatever can be linked with certainty as soon as new wallets or real accounts appear.
    LaunchedEffect(source, shadowAccounts, links, real) {
        val plan = planAutoLinks(source, shadowAccounts, links, real)
        if (plan.isNotEmpty()) {
            runCatchingUnlessCancelled { importEngine.applyAutoLinks(plan) }.onFailure { error = "Automatic linking failed: ${it.message}" }
        }
    }

    var refreshKey by remember { mutableStateOf(0) }

    fun refresh() {
        cache.invalidate(source.name)
        refreshKey++
    }
    var result by remember { mutableStateOf(cache.peek(source.name, writeVersion)) }
    var progress by remember { mutableStateOf<Progress?>(null) }
    LaunchedEffect(source, shadowIds, links, refreshKey, writeVersion) {
        cache.get(source.name, shadowIds, links, writeVersion)?.let {
            result = it
            return@LaunchedEffect
        }
        // The flows above start empty, so these inputs may just not have loaded; keep what was shown until they do.
        if (shadowIds.isEmpty() && links.isEmpty()) {
            cache.peek(source.name, writeVersion)?.let {
                result = it
                return@LaunchedEffect
            }
        }
        result = null
        progress = Progress("Loading ${source.name} transactions")
        val sourceLegs = reconciliationRepository.getLegs(shadowIds)
        progress = Progress("Loading Money Manager transactions")
        val realLegs = reconciliationRepository.getLegs(links.map { it.realAccountId }.toSet())
        val computed = withContext(Dispatchers.Default) { reconcile(sourceLegs, links, realLegs, onProgress = { progress = it }) }
        cache.put(source.name, shadowIds, links, writeVersion, computed)
        result = computed
    }

    val unlinked = remember(source, shadowAccounts, links, real) { unlinkedWallets(source, shadowAccounts, links, real) }
    // One new real account per wallet still needing attention, named after the wallet.
    val createPlan =
        remember(source, unlinked, accounts) {
            planAccountCreation(source, unlinked.filterNot { it.wallet.autoLinkDeclined }.map { it.wallet }, accounts)
        }
    var hideUnknownAssets by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(ResultTab.UNMATCHED) }
    val expanded = remember(source) { mutableStateMapOf<String, Boolean>() }

    fun setLinks(
        shadow: AccountId,
        realIds: Set<AccountId>,
    ) {
        scope.launch {
            runCatchingUnlessCancelled { importEngine.setReconciliationLinks(shadow, realIds) }
                .onSuccess { error = null }
                .onFailure { error = it.message }
        }
    }

    var creatingAll by remember { mutableStateOf(false) }

    // Legs aren't a Flow, so every write reloads them.
    fun write(
        what: String,
        block: suspend () -> Unit,
    ) {
        scope.launch {
            runCatchingUnlessCancelled { block() }
                .onSuccess {
                    error = null
                    refresh()
                }.onFailure { error = "$what failed: ${it.message}" }
        }
    }

    fun storeMatch(legs: List<ReconciliationLeg>) =
        write("Matching") { importEngine.createReconciliationMatch(legs.map { it.transactionRef }.distinct()) }

    // Ignoring a source row excludes every transaction of its movement (a trade and its fee, say).
    suspend fun applyIgnored(
        legs: List<ReconciliationLeg>,
        ignored: Boolean,
    ) {
        val reason = IGNORED_IN_RECONCILIATION.takeIf { ignored }
        val refs = legs.map { it.transactionRef }.distinct()
        refs.filterIsInstance<TradeId>().forEach { importEngine.setTradeExcluded(it, reason) }
        val transferIds = refs.filterIsInstance<TransferId>()
        if (transferIds.isNotEmpty()) {
            transactionRepository.getTransactionsByIds(transferIds).values.forEach { importEngine.setTransferExcluded(it, reason) }
        }
    }

    fun setIgnored(
        legs: List<ReconciliationLeg>,
        ignored: Boolean,
    ) = write(if (ignored) "Ignoring" else "Un-ignoring") { applyIgnored(legs, ignored) }

    // A bulk run is a background task: it shows in the task panel with a progress bar, and the legs reload when it ends.
    val taskManager = LocalTaskManager.current
    val tasks by taskManager.tasks.collectAsState()
    val bulkKey = "reconciliation-bulk-${source.name}"
    // Set on the click itself: the task list only shows the run a recomposition later.
    var bulkSubmitted by remember { mutableStateOf(false) }
    val bulkRunning = bulkSubmitted || tasks.any { it.key == bulkKey && it.isRunning }

    fun <T> runBulk(
        title: String,
        what: String,
        items: List<T>,
        apply: suspend (List<T>) -> Unit,
    ) {
        if (bulkRunning) return
        bulkSubmitted = true
        val id =
            taskManager.start(bulkKey, title, "Starting") {
                try {
                    var done = 0
                    items.chunked(BULK_CHUNK).forEach { chunk ->
                        report(Progress.counted(what, done, items.size))
                        apply(chunk)
                        done += chunk.size
                    }
                    report(Progress.counted(what, done, items.size))
                    "$done done"
                } finally {
                    refresh()
                    bulkSubmitted = false
                }
            }
        if (id == null) bulkSubmitted = false
    }

    fun storeMatches(matches: List<LegMatch>) =
        runBulk("Accepting ${matches.size} matches", "Accepting matches", matches) { chunk ->
            importEngine.createReconciliationMatches(chunk.map { it.memberTransactions() })
        }

    fun ignoreAll(legs: List<ReconciliationLeg>) =
        runBulk("Ignoring ${legs.size} rows", "Ignoring rows", legs) { chunk -> applyIgnored(chunk, ignored = true) }

    fun createAll(plan: Map<AccountId, String>) {
        creatingAll = true
        scope.launch {
            runCatchingUnlessCancelled { importEngine.createAndLinkAccounts(plan, Clock.System.now()) }
                .onSuccess { error = null }
                .onFailure { error = "Creating accounts failed: ${it.message}" }
            creatingAll = false
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { SummaryHeader(source, result, hideUnknownAssets, onRefresh = { refresh() }) }
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
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) {
                    Text(
                        heading,
                        style = MaterialTheme.typography.titleSmall,
                        color =
                            if (wallets ===
                                needsAttention
                            ) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        modifier = Modifier.weight(1f),
                    )
                    if (wallets === needsAttention) {
                        TextButton(onClick = { createAll(createPlan) }, enabled = createPlan.isNotEmpty() && !creatingAll) {
                            Text("Create all (${createPlan.size})")
                        }
                    }
                }
            }
            items(wallets, key = { "unlinked-${it.wallet.accountId.id}" }) { wallet ->
                UnlinkedWalletCard(
                    wallet = wallet,
                    walletName = source.walletName(wallet.wallet),
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    personRepository = personRepository,
                    existingNames = existingNames,
                    // "Create all" links in a second batch that would overwrite a link made meanwhile.
                    enabled = !creatingAll,
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
            item { ReconciliationProgress(progress) }
            return@LazyColumn
        }
        item {
            SecondaryTabRow(selectedTabIndex = tab.ordinal, modifier = Modifier.padding(top = 12.dp)) {
                ResultTab.entries.forEach { entry ->
                    Tab(selected = tab == entry, onClick = { tab = entry }, text = { Text(entry.label) })
                }
            }
        }
        val walletName: (AccountId) -> String = { id ->
            shadowAccounts.firstOrNull { it.accountId == id }?.let(source::walletName) ?: accountNames[id].orEmpty()
        }
        when (tab) {
            ResultTab.UNMATCHED -> {
                // Accept all stores the exact matches under the same header too, not only the near ones listed here.
                sectionHeading("Near matches (${loaded.nearMatches.size})") {
                    loaded.unstoredMatches().let { BulkButton("Accept all", it.size, enabled = !bulkRunning) { storeMatches(it) } }
                }
                legGroups(
                    prefix = "near",
                    groups = groupByWalletAndMonth(loaded.nearMatches, { it.sourceLeg.accountId }, { it.sourceLeg.timestamp }, walletName),
                    expanded = expanded,
                    empty = "No near matches: every match agrees to the second.",
                    headerAction = { _, wallet, month ->
                        loaded
                            .unstoredMatches(
                                wallet,
                                month,
                            ).let { BulkButton("Accept all", it.size, enabled = !bulkRunning) { storeMatches(it) } }
                    },
                ) { match ->
                    MatchRow(match, accountNames, onOpenLeg) {
                        TextButton(
                            onClick = {
                                write(
                                    "Matching",
                                ) { importEngine.createReconciliationMatches(listOf(match.memberTransactions())) }
                            },
                            enabled = !bulkRunning,
                        ) { Text("Accept") }
                    }
                }
                val onlyInSource = loaded.missingInMm.filter { !hideUnknownAssets || !it.assetUnknownToMm }
                sectionHeading("Only in ${source.name} (${onlyInSource.size})") {
                    BulkButton("Ignore all", onlyInSource.size, enabled = !bulkRunning) { ignoreAll(loaded.movementsOf(onlyInSource)) }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = hideUnknownAssets, onCheckedChange = { hideUnknownAssets = it })
                        Text("Hide assets Money Manager has never seen on the linked accounts (e.g. spam airdrops)")
                    }
                }
                legGroups(
                    prefix = "source-only",
                    groups = groupByWalletAndMonth(onlyInSource, { it.walletAccountId }, { it.leg.timestamp }, walletName),
                    expanded = expanded,
                    empty = "Everything ${source.name} has on the linked wallets is in Money Manager.",
                    headerAction = {
                        rows,
                        _,
                        _,
                        ->
                        BulkButton("Ignore all", rows.size, enabled = !bulkRunning) { ignoreAll(loaded.movementsOf(rows)) }
                    },
                ) { unmatched ->
                    LegRow(unmatched.leg, accountNames, onOpenLeg) {
                        TextButton(
                            onClick = { setIgnored(loaded.movementOf(unmatched), ignored = true) },
                            enabled = !bulkRunning,
                        ) { Text("Ignore") }
                        TextButton(
                            onClick = { pickerState.open(unmatched.pickerKey) },
                            enabled = !bulkRunning,
                        ) { Text("Select matching transactions…") }
                    }
                }
                sectionHeading("Only in Money Manager (${loaded.missingInSource.size})")
                legGroups(
                    prefix = "mm-only",
                    groups = groupByWalletAndMonth(loaded.missingInSource, { it.walletAccountId }, { it.leg.timestamp }, walletName),
                    expanded = expanded,
                    empty = "Everything on the linked accounts in ${source.name}'s date range is in ${source.name}.",
                ) { LegRow(it.leg, accountNames, onOpenLeg) }
            }

            ResultTab.MATCHED -> {
                sectionHeading("Matched (${loaded.matched.size})") {
                    loaded.unstoredMatches().filter { it.settled }.let {
                        BulkButton(
                            "Accept all",
                            it.size,
                            enabled = !bulkRunning,
                        ) { storeMatches(it) }
                    }
                }
                legGroups(
                    prefix = "matched",
                    groups = groupByWalletAndMonth(loaded.matched, { it.sourceLeg.accountId }, { it.sourceLeg.timestamp }, walletName),
                    expanded = expanded,
                    empty = "Nothing matched yet — link the wallets above.",
                    // Only the rows the matcher found: a stored one is already accepted.
                    headerAction = { _, wallet, month ->
                        loaded
                            .unstoredMatches(
                                wallet,
                                month,
                            ).filter { it.settled }
                            .let { BulkButton("Accept all", it.size, enabled = !bulkRunning) { storeMatches(it) } }
                    },
                ) { match ->
                    val matchId = match.matchId
                    MatchRow(
                        match,
                        accountNames,
                        onOpenLeg,
                        action =
                            if (matchId == null) {
                                null
                            } else {
                                {
                                    TextButton(
                                        onClick = { write("Unmatching") { importEngine.deleteRelationship(RelationshipId(matchId)) } },
                                        enabled = !bulkRunning,
                                    ) {
                                        Text("Unmatch")
                                    }
                                }
                            },
                    )
                }
                sectionHeading("Ignored (${loaded.ignored.size})")
                legGroups(
                    prefix = "ignored",
                    groups = groupByWalletAndMonth(loaded.ignored, { it.walletAccountId }, { it.leg.timestamp }, walletName),
                    expanded = expanded,
                    empty = "No ${source.name} rows ignored.",
                ) { ignored ->
                    LegRow(ignored.leg, accountNames, onOpenLeg) {
                        TextButton(
                            onClick = { setIgnored(listOf(ignored.leg), ignored = false) },
                            enabled = !bulkRunning,
                        ) { Text("Un-ignore") }
                    }
                }
            }
        }
    }

    val loaded = result
    val picked = pickerState.pickedKey?.let { key -> loaded?.missingInMm?.firstOrNull { it.pickerKey == key } }
    if (loaded != null && picked != null) {
        val movement = remember(loaded, picked) { loaded.movementOf(picked) }
        MatchPickerDialog(
            sourceName = source.name,
            movement = movement,
            wallet = picked.walletAccountId,
            result = loaded,
            accountNames = accountNames,
            state = pickerState,
            onDismiss = pickerState::close,
            onIgnore = {
                pickerState.close()
                setIgnored(movement, ignored = true)
            },
            onConfirm = { selected ->
                pickerState.close()
                storeMatch(movement + selected)
            },
            onBookFee = { fee, selected ->
                pickerState.close()
                write("Adding the fee") {
                    val feeId = importEngine.createManualFee(fee.account, fee.fee, fee.timestamp)
                    importEngine.createReconciliationMatch((movement + selected).map { it.transactionRef } + feeId)
                }
            },
            onOpenLeg = onOpenLeg,
            onOpenSource = onOpenSource,
            copyPlan = remember(movement, shadowAccounts, links, accountNames) { planCopy(movement, shadowAccounts, links, accountNames) },
            onCopy = { plan ->
                pickerState.close()
                write("Copying to Money Manager") { importEngine.copyToMoneyManager(movement, plan) }
            },
        )
    }
}

private fun LazyListScope.sectionHeading(
    text: String,
    action: (@Composable () -> Unit)? = null,
) {
    item(key = "heading-$text".substringBefore(" (")) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) {
            Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            action?.invoke()
        }
    }
}

/** A header's bulk button, covering the [count] rows beneath it; absent when there are none. */
@Composable
private fun BulkButton(
    label: String,
    count: Int,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    if (count > 0) TextButton(onClick = onClick, enabled = enabled) { Text("$label ($count)") }
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
                AssistChip(onClick = {}, label = { Text("Matched: ${result.matched.size}") })
                AssistChip(onClick = {}, label = { Text("Near matches: ${result.nearMatches.size}") })
                AssistChip(onClick = {}, label = { Text("Only in ${source.name}: $missingInMm") })
                AssistChip(onClick = {}, label = { Text("Only in Money Manager: ${result.missingInSource.size}") })
                AssistChip(onClick = {}, label = { Text("Ignored: ${result.ignored.size}") })
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
    existingNames: Set<String>,
    enabled: Boolean,
    isReal: (Account) -> Boolean,
    onLink: (AccountId) -> Unit,
) {
    val declined = wallet.wallet.autoLinkDeclined
    var showCreateDialog by remember { mutableStateOf(false) }
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
                    "Pick the Money Manager account this wallet mirrors, or create it."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            wallet.suggestion?.let { suggestion ->
                TextButton(
                    onClick = { onLink(suggestion.id) },
                    enabled = enabled,
                ) { Text("Link to suggested account \"${suggestion.name}\"") }
            }
            AccountPicker(
                selectedAccountId = null,
                onAccountSelected = onLink,
                label = "Link to account",
                accountRepository = accountRepository,
                categoryRepository = categoryRepository,
                personRepository = personRepository,
                enabled = enabled,
                accountFilter = isReal,
            )
            TextButton(onClick = { showCreateDialog = true }, enabled = enabled) { Text("Create new account") }
        }
    }
    if (showCreateDialog) {
        CreateAccountDialog(
            categoryRepository = categoryRepository,
            personRepository = personRepository,
            onDismiss = { showCreateDialog = false },
            onAccountCreated = onLink,
            initialName = walletName,
            existingNames = existingNames,
        )
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
    headerAction: (@Composable (items: List<T>, wallet: AccountId, month: String?) -> Unit)? = null,
    row: @Composable (T) -> Unit,
) {
    if (groups.isEmpty()) {
        item { Text(empty, modifier = Modifier.padding(vertical = 12.dp)) }
        return
    }
    groups.forEach { group ->
        val walletKey = "$prefix-${group.walletAccountId.id}"
        item(key = walletKey) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${if (expanded[walletKey] == true) "▾" else "▸"} ${group.walletName} (${group.count})",
                    style = MaterialTheme.typography.titleSmall,
                    modifier =
                        Modifier
                            .weight(1f)
                            .clickable { expanded[walletKey] = expanded[walletKey] != true }
                            .padding(vertical = 8.dp),
                )
                headerAction?.invoke(group.months.flatMap { it.items }, group.walletAccountId, null)
            }
        }
        if (expanded[walletKey] != true) return@forEach
        group.months.forEach { month ->
            val monthKey = "$walletKey-${month.month}"
            item(key = monthKey) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${if (expanded[monthKey] == true) "▾" else "▸"} ${month.month} (${month.items.size})",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier =
                            Modifier
                                .weight(1f)
                                .clickable { expanded[monthKey] = expanded[monthKey] != true }
                                .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                    )
                    headerAction?.invoke(month.items, group.walletAccountId, month.month)
                }
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
    actions: (@Composable () -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onOpenLeg(leg) }.padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(leg.timestamp.displayDateTime(), modifier = Modifier.width(140.dp), style = MaterialTheme.typography.bodySmall)
            Text(formatAmount(leg.amount), modifier = Modifier.width(180.dp), style = MaterialTheme.typography.bodySmall)
            Text(
                leg.description + (leg.counterpartyAccountId?.let { "  ·  ${accountNames[it].orEmpty()}" } ?: "  ·  trade"),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
        }
        // On a line of their own, so a phone-width row still has room for them.
        if (actions != null) {
            Row(modifier = Modifier.align(Alignment.End)) { actions() }
        }
    }
}

@Composable
private fun MatchRow(
    match: LegMatch,
    accountNames: Map<AccountId, String>,
    onOpenLeg: (ReconciliationLeg) -> Unit,
    action: (@Composable () -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        match.sourceLegs.forEach { LegRow(it, accountNames, onOpenLeg) }
        match.realLegs.forEach { real ->
            Row {
                Text("↳ ", style = MaterialTheme.typography.bodySmall)
                LegRow(real, accountNames, onOpenLeg)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                match.matchId != null -> "Matched by hand"
                !match.exact -> "Δt ${formatDelta(match.timeDelta)}"
                else -> null
            }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
            action?.invoke()
        }
    }
}

/**
 * [runCatching] that lets cancellation propagate. A write here routinely updates the very flows its
 * effect is keyed on, restarting (so cancelling) it before `import()` returns; that is not a failure.
 */
internal inline fun <T> runCatchingUnlessCancelled(block: () -> T): Result<T> =
    runCatching(block).onFailure { if (it is CancellationException) throw it }

internal fun formatDelta(delta: Duration): String = delta.absoluteValue.toString().let { if (delta.isNegative()) "-$it" else "+$it" }

/** The bar and step shown while the legs load and [reconcile] runs through its passes. */
@Composable
private fun ReconciliationProgress(progress: Progress?) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val fraction = progress?.fraction
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            progress?.detail.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

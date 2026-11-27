package com.moneymanager.ui.screens.reconciliation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import com.moneymanager.importengineapi.deleteRelationship
import com.moneymanager.importengineapi.setReconciliationLinks
import com.moneymanager.importengineapi.setTradeExcluded
import com.moneymanager.importengineapi.setTransferExcluded
import com.moneymanager.presenter.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.presenter.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.reconciliation.LegMatch
import com.moneymanager.reconciliation.ReconciliationResult
import com.moneymanager.reconciliation.UnlinkedWallet
import com.moneymanager.reconciliation.UnmatchedSourceLeg
import com.moneymanager.reconciliation.acceptMatches
import com.moneymanager.reconciliation.applyAutoLinks
import com.moneymanager.reconciliation.copyToMoneyManager
import com.moneymanager.reconciliation.createAndLinkAccounts
import com.moneymanager.reconciliation.planAccountCreation
import com.moneymanager.reconciliation.planAutoLinks
import com.moneymanager.reconciliation.planCopy
import com.moneymanager.reconciliation.planSuggestedLinks
import com.moneymanager.reconciliation.realAccounts
import com.moneymanager.reconciliation.reconcile
import com.moneymanager.reconciliation.unlinkedWallets
import com.moneymanager.tasks.Progress
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.components.CreateAccountDialog
import com.moneymanager.ui.foundation.LocalImportEngine
import com.moneymanager.ui.foundation.ReconciliationFocusState
import com.moneymanager.ui.foundation.rememberPersistedState
import com.moneymanager.ui.tasks.LocalTaskManager
import com.moneymanager.ui.util.displayDateTime
import com.moneymanager.ui.util.formatAmount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.time.Clock
import kotlin.time.Duration

/** Rows a bulk run writes per engine call: one transaction each, with the progress bar moving between them. */
private const val BULK_CHUNK = 50

@Serializable
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
    refreshViews: suspend () -> Unit = {},
    pickerState: MatchPickerState = remember { MatchPickerState() },
    cache: ReconciliationCache = remember { ReconciliationCache() },
    focus: ReconciliationFocusState = remember { ReconciliationFocusState() },
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
    var selectedName by
        rememberPersistedState(
            "reconciliation.source",
            String.serializer(),
            restore = { name -> if (loadedSources.any { it.name == name }) name else loadedSources.first().name },
        ) { loadedSources.first().name }
    val source = loadedSources.firstOrNull { it.name == selectedName } ?: loadedSources.first()
    // A link elsewhere asked for a source: show it, and SourceReconciliation then opens the wallet and month.
    LaunchedEffect(focus.request, loadedSources) {
        focus.request?.let { request -> if (loadedSources.any { it.name == request.sourceName }) selectedName = request.sourceName }
    }

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
            refreshViews = refreshViews,
            pickerState = pickerState,
            cache = cache,
            focus = focus,
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
    refreshViews: suspend () -> Unit,
    pickerState: MatchPickerState,
    cache: ReconciliationCache,
    focus: ReconciliationFocusState,
) {
    val importEngine = LocalImportEngine.current
    val writeVersion by importEngine.writeVersion.collectAsState()
    val scope = rememberSchemaAwareCoroutineScope()
    val loadedShadows by rememberFlowAsStateWithSchemaErrorHandling(initial = null) {
        reconciliationRepository.getShadowAccounts()
    }
    val loadedLinks by rememberFlowAsStateWithSchemaErrorHandling(initial = null) {
        reconciliationRepository.getLinks()
    }
    val loadedAccounts by rememberFlowAsStateWithSchemaErrorHandling(initial = null) {
        accountRepository.getAllAccounts()
    }
    val allShadowAccounts = loadedShadows.orEmpty()
    val allLinks = loadedLinks.orEmpty()
    val accounts = loadedAccounts.orEmpty()

    val shadowAccounts = remember(allShadowAccounts, source) { allShadowAccounts.filter { it.sourceName == source.name } }
    val shadowIds = remember(shadowAccounts) { shadowAccounts.mapTo(mutableSetOf()) { it.accountId } }
    val links = remember(allLinks, shadowIds) { allLinks.filter { it.shadowAccountId in shadowIds } }
    val real = remember(accounts, allShadowAccounts) { realAccounts(accounts, allShadowAccounts) }
    val accountNames = remember(accounts) { accounts.associate { it.id to it.name } }
    val allShadowIds = remember(allShadowAccounts) { allShadowAccounts.mapTo(mutableSetOf()) { it.accountId } }
    val existingNames = remember(accounts) { accounts.mapTo(mutableSetOf()) { it.name } }
    var error by remember { mutableStateOf<String?>(null) }

    val taskManager = LocalTaskManager.current
    val tasks by taskManager.tasks.collectAsState()
    val bulkKey = "reconciliation-bulk-${source.name}"
    // Set on the click itself: the task list only shows the run a recomposition later.
    var bulkSubmitted by remember { mutableStateOf(false) }
    val bulkTask = tasks.firstOrNull { it.key == bulkKey && it.isRunning }
    val bulkRunning = bulkSubmitted || bulkTask != null

    var linkingAll by remember(source) { mutableStateOf(false) }
    val writing = bulkRunning || linkingAll
    val linkableIds =
        remember(source, shadowAccounts) {
            shadowAccounts.filter(source::isLinkable).mapTo(mutableSetOf()) { it.accountId }
        }

    var refreshKey by remember { mutableStateOf(0) }
    var walletRefreshKey by remember { mutableStateOf(0) }

    fun refresh() {
        cache.invalidate(source.name)
        refreshKey++
    }
    var result by remember { mutableStateOf(cache.peek(source.name, writeVersion)) }
    // The wallets and links the result was made for: the one shown at first may be a kept result for inputs not loaded yet.
    var resultFor by remember { mutableStateOf<Pair<Set<AccountId>, List<ReconciliationLink>>?>(null) }
    var progress by remember { mutableStateOf<Progress?>(null) }
    val comparisonInputsLoaded = loadedShadows != null && loadedLinks != null
    LaunchedEffect(source, shadowIds, links, refreshKey, writeVersion, writing, linkableIds, comparisonInputsLoaded) {
        // Each chunk bumps writeVersion. Reload once the bulk task has finished.
        if (writing || !comparisonInputsLoaded) return@LaunchedEffect
        cache.get(source.name, shadowIds, links, writeVersion)?.let {
            result = it
            resultFor = shadowIds to links
            return@LaunchedEffect
        }
        result = null
        progress = Progress("Loading ${source.name} transactions")
        val sourceLegs = reconciliationRepository.getLegs(shadowIds)
        progress = Progress("Loading Money Manager transactions")
        val realLegs = reconciliationRepository.getLegs(links.map { it.realAccountId }.toSet())
        val computed =
            withContext(Dispatchers.Default) {
                val unlinkedIds = linkableIds - links.map { it.shadowAccountId }.toSet()
                reconcile(sourceLegs, links, realLegs, onProgress = {
                    ensureActive()
                    progress = it
                }).copy(unlinkedLegs = sourceLegs.filter { it.accountId in unlinkedIds && !it.isExcluded })
            }
        cache.put(source.name, shadowIds, links, writeVersion, computed)
        result = computed
        resultFor = shadowIds to links
    }

    val linkInputs =
        if (loadedShadows == null || loadedLinks == null || loadedAccounts == null) {
            null
        } else {
            WalletLinkInputs(source, shadowAccounts, links, real, accounts)
        }
    var walletPlans by remember(cache, linkInputs, walletRefreshKey) {
        mutableStateOf(linkInputs?.let(cache::getWalletPlans))
    }
    var pendingAutomatic by remember(cache, linkInputs, walletRefreshKey) {
        mutableStateOf<Map<AccountId, AccountId>?>(null)
    }
    LaunchedEffect(cache, linkInputs, walletRefreshKey) {
        val inputs = linkInputs ?: return@LaunchedEffect
        if (walletPlans != null) return@LaunchedEffect
        val (automatic, prepared) =
            withContext(Dispatchers.Default) {
                val automatic = planAutoLinks(source, shadowAccounts, links, real)
                val unlinked = unlinkedWallets(source, shadowAccounts, links, real)
                val creation = planAccountCreation(source, unlinked.filterNot { it.wallet.autoLinkDeclined }.map { it.wallet }, accounts)
                automatic to
                    WalletPlans(
                        unlinked,
                        creation,
                        planSuggestedLinks(
                            unlinked,
                            links.mapTo(mutableSetOf()) { it.realAccountId } + automatic.values,
                        ),
                    )
            }
        walletPlans = prepared
        if (automatic.isEmpty()) cache.putWalletPlans(inputs, prepared) else pendingAutomatic = automatic
    }
    LaunchedEffect(cache, linkInputs, walletRefreshKey, writing, pendingAutomatic) {
        val inputs = linkInputs ?: return@LaunchedEffect
        val automatic = pendingAutomatic ?: return@LaunchedEffect
        val prepared = walletPlans ?: return@LaunchedEffect
        if (writing) return@LaunchedEffect
        runCatchingUnlessCancelled { importEngine.applyAutoLinks(automatic) }
            .onSuccess { cache.putWalletPlans(inputs, prepared) }
            .onFailure { error = "Automatic linking failed: ${it.message}" }
        pendingAutomatic = null
    }
    val unlinked = walletPlans?.unlinked.orEmpty()
    val createPlan = walletPlans?.creation.orEmpty()
    var accountSearch by rememberSaveable(source.name) { mutableStateOf("") }
    val accountQuery = accountSearch.trim()
    val linksByWallet = remember(links) { links.groupBy({ it.shadowAccountId }, { it.realAccountId }) }
    val visibleUnlinked =
        remember(unlinked, accountQuery) {
            unlinked.filter { it.wallet.name.contains(accountQuery, ignoreCase = true) }
        }
    val visibleLinked =
        remember(shadowAccounts, linksByWallet, accountNames, accountQuery) {
            shadowAccounts
                .filter { wallet ->
                    val realIds = linksByWallet[wallet.accountId] ?: return@filter false
                    wallet.name.contains(accountQuery, ignoreCase = true) ||
                        realIds.any { accountNames[it]?.contains(accountQuery, ignoreCase = true) == true }
                }.sortedBy { it.name.lowercase() }
        }
    val (withSuggestion, withoutSuggestion) = remember(visibleUnlinked) { visibleUnlinked.partition { it.suggestion != null } }
    var suggestionTab by rememberSaveable(source.name) { mutableStateOf<Boolean?>(null) }
    val showSuggestions = suggestionTab ?: unlinked.any { it.suggestion != null }
    val walletsInTab = if (showSuggestions) withSuggestion else withoutSuggestion
    val walletIdsInTab = remember(walletsInTab) { walletsInTab.mapTo(mutableSetOf()) { it.wallet.accountId } }
    val visibleCreatePlan = remember(createPlan, walletIdsInTab) { createPlan.filterKeys { it in walletIdsInTab } }
    val suggestedLinks = walletPlans?.suggested.orEmpty()
    val visibleLinkPlan = remember(suggestedLinks, walletIdsInTab) { suggestedLinks.filterKeys { it in walletIdsInTab } }
    var hideUnknownAssets by rememberPersistedState("reconciliation.hideUnknownAssets", Boolean.serializer()) { true }
    var tab by rememberPersistedState("reconciliation.tab", ResultTab.serializer()) { ResultTab.UNMATCHED }
    val walletNames =
        remember(source, shadowAccounts, accountNames) {
            accountNames + shadowAccounts.associate { it.accountId to source.walletName(it) }
        }
    var groups by remember(result, hideUnknownAssets, walletNames) { mutableStateOf<ReconciliationGroups?>(null) }
    LaunchedEffect(result, hideUnknownAssets, walletNames) {
        val comparison = result ?: return@LaunchedEffect
        groups =
            withContext(Dispatchers.Default) {
                ReconciliationGroups(comparison, hideUnknownAssets) { walletNames[it].orEmpty() }
            }
    }
    var showUnlinked by rememberSaveable(source.name) { mutableStateOf(false) }
    var linkedOpen by remember(source, accountQuery.isNotEmpty()) { mutableStateOf(accountQuery.isNotEmpty()) }
    val expanded = remember(source) { mutableStateMapOf<String, Boolean>() }
    val listState = rememberLazyListState()
    // Where each row of the list is, as the list was last built, to scroll to the one a link named.
    val indexByKey = remember { mutableMapOf<Any, Int>() }
    var scrollToLeaf by remember { mutableStateOf<Long?>(null) }
    // The movement a link named stays marked, as the CSV screen marks the row it was sent to.
    var highlightedLeaf by remember(source) { mutableStateOf<Long?>(null) }
    // The movement a link named: found among the comparison's lists, its tab and tree opened down to it, then scrolled to.
    LaunchedEffect(focus.request, source, result, resultFor, shadowIds, links, groups) {
        val request = focus.request ?: return@LaunchedEffect
        if (request.sourceName != source.name) return@LaunchedEffect
        val comparison = result ?: return@LaunchedEffect
        if (groups == null) return@LaunchedEffect
        // Wait for a comparison of the wallets and links as loaded: finding the movement in one made too soon would fail and give up.
        if (shadowIds.isEmpty() || resultFor != (shadowIds to links)) return@LaunchedEffect
        val at = locateLeaf(comparison, request.transactionId, hideUnknownAssets)
        if (at != null) {
            tab = at.tab
            showUnlinked = at.prefix == "unlinked-source"
            val wallet = "${at.prefix}-${at.wallet.id}"
            expanded[wallet] = true
            expanded["$wallet-${at.month}"] = true
            scrollToLeaf = request.transactionId
            highlightedLeaf = request.transactionId
        } else {
            // Not in any list (hidden, or not compared): at least open the wallet and month it belongs to.
            tab = ResultTab.UNMATCHED
            showUnlinked = request.walletAccountId !in linksByWallet
            listOf("near", "source-only", "unlinked-source", "mm-only", "accepted", "not-accepted", "ignored").forEach { prefix ->
                val wallet = "$prefix-${request.walletAccountId.id}"
                expanded[wallet] = true
                expanded["$wallet-${request.month}"] = true
            }
        }
        focus.clear()
    }
    // The list is rebuilt for the new tab and tree a frame or two later, so wait until the row has an index.
    LaunchedEffect(scrollToLeaf, groups) {
        val transactionId = scrollToLeaf ?: return@LaunchedEffect
        repeat(SCROLL_ATTEMPTS) {
            val index = indexByKey.entries.firstOrNull { leafHolds(it.key, transactionId) }?.value
            if (index != null) {
                listState.scrollToItem(index)
                // Put it in the middle: it sits at the top now, so move the list back by half of what is left of the screen.
                withFrameNanos { }
                val layout = listState.layoutInfo
                layout.visibleItemsInfo.firstOrNull { it.index == index }?.let { row ->
                    val viewport = layout.viewportEndOffset - layout.viewportStartOffset
                    listState.scrollBy(-(viewport - row.size).coerceAtLeast(0) / 2f)
                }
                scrollToLeaf = null
                return@LaunchedEffect
            }
            withFrameNanos { }
        }
        scrollToLeaf = null
    }

    // Shown on the wallet the person was linking, which may be far below the list's top.
    val linkErrors = remember(source) { mutableStateMapOf<AccountId, String>() }

    fun setLinks(
        shadow: AccountId,
        realIds: Set<AccountId>,
    ) {
        scope.launch {
            runCatchingUnlessCancelled { importEngine.setReconciliationLinks(shadow, realIds) }
                .onSuccess { linkErrors.remove(shadow) }
                .onFailure { linkErrors[shadow] = "Linking failed: ${it.message}" }
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
        write("Matching") { importEngine.acceptMatches(listOf(legs.map { it.transactionRef }.distinct())) }

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

    fun <T> runBulk(
        title: String,
        what: String,
        items: List<T>,
        apply: suspend (List<T>) -> Unit,
    ) {
        if (writing) return
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
            importEngine.acceptMatches(chunk.map { it.memberTransactions() })
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

    fun linkAll() {
        linkingAll = true
        scope.launch {
            try {
                runCatchingUnlessCancelled { importEngine.applyAutoLinks(visibleLinkPlan) }
                    .onSuccess { error = null }
                    .onFailure { error = "Linking accounts failed: ${it.message}" }
            } finally {
                linkingAll = false
            }
        }
    }

    when {
        bulkRunning -> ReconciliationProgress(bulkTask?.progress ?: Progress("Starting"))
        linkingAll -> ReconciliationProgress(Progress("Linking suggested accounts"))
        walletPlans == null -> ReconciliationProgress(Progress("Finding account links"))
        result == null -> ReconciliationProgress(progress ?: Progress("Loading transactions"))
        groups == null -> ReconciliationProgress(Progress("Grouping reconciliation results"))
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        IndexingScope(this, indexByKey).run {
            item {
                SummaryHeader(source, result, hideUnknownAssets, onRefresh = {
                    cache.invalidateWalletPlans(source.name)
                    walletRefreshKey++
                    refresh()
                })
            }
            error?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 4.dp)) }
            }
            if (unlinked.isNotEmpty() || linksByWallet.isNotEmpty() || accountSearch.isNotEmpty()) {
                item(key = "account-search") {
                    OutlinedTextField(
                        value = accountSearch,
                        onValueChange = { accountSearch = it },
                        label = { Text("Search accounts") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                if (visibleUnlinked.isEmpty() && visibleLinked.isEmpty() && accountQuery.isNotEmpty()) {
                    item { Text("No accounts match your search", modifier = Modifier.padding(vertical = 12.dp)) }
                }
            }
            if (unlinked.isNotEmpty()) {
                item(key = "wallet-suggestion-tabs") {
                    SecondaryTabRow(selectedTabIndex = if (showSuggestions) 0 else 1, modifier = Modifier.padding(top = 8.dp)) {
                        Tab(
                            selected = showSuggestions,
                            onClick = { suggestionTab = true },
                            text = { Text("With suggestion (${withSuggestion.size})") },
                        )
                        Tab(
                            selected = !showSuggestions,
                            onClick = { suggestionTab = false },
                            text = { Text("Without suggestion (${withoutSuggestion.size})") },
                        )
                    }
                }
                if (walletsInTab.isEmpty() && (accountQuery.isEmpty() || visibleUnlinked.isNotEmpty() || visibleLinked.isNotEmpty())) {
                    item {
                        Text("No wallets in this tab", modifier = Modifier.padding(vertical = 12.dp))
                    }
                }
            }
            // Wallets the user deliberately unlinked aren't a problem to fix, just not compared.
            val (declined, needsAttention) = walletsInTab.partition { it.wallet.autoLinkDeclined }
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
                        if (wallets === needsAttention && visibleLinkPlan.isNotEmpty()) {
                            TextButton(onClick = ::linkAll, enabled = !creatingAll && !writing) {
                                Text("Link all (${visibleLinkPlan.size})")
                            }
                        }
                        if (wallets === needsAttention) {
                            TextButton(
                                onClick = { createAll(visibleCreatePlan) },
                                enabled = visibleCreatePlan.isNotEmpty() && !creatingAll && !writing,
                            ) {
                                Text("Create all (${visibleCreatePlan.size})")
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
                        enabled = !creatingAll && !writing,
                        isReal = { it.id !in allShadowIds },
                        linkError = linkErrors[wallet.wallet.accountId],
                        onLink = { realId -> setLinks(wallet.wallet.accountId, setOf(realId)) },
                    )
                }
            }
            linkedWallets(
                source = source,
                linked = visibleLinked,
                linksByWallet = linksByWallet,
                open = linkedOpen,
                onToggle = { linkedOpen = !linkedOpen },
                enabled = !creatingAll && !writing,
                accountNames = accountNames,
                counts = groups?.counts.orEmpty(),
                accountRepository = accountRepository,
                categoryRepository = categoryRepository,
                personRepository = personRepository,
                isReal = { it.id !in allShadowIds },
                linkErrors = linkErrors,
                onSetLinks = ::setLinks,
            )
            val loaded = result ?: return@run
            val prepared = groups ?: return@run
            item {
                SecondaryTabRow(selectedTabIndex = tab.ordinal, modifier = Modifier.padding(top = 12.dp)) {
                    ResultTab.entries.forEach { entry ->
                        Tab(selected = tab == entry, onClick = { tab = entry }, text = { Text(entry.label) })
                    }
                }
            }
            when (tab) {
                ResultTab.UNMATCHED -> {
                    item {
                        SecondaryTabRow(selectedTabIndex = if (showUnlinked) 1 else 0, modifier = Modifier.padding(top = 4.dp)) {
                            Tab(
                                selected = !showUnlinked,
                                onClick = { showUnlinked = false },
                                text = { Text("Linked accounts") },
                            )
                            Tab(
                                selected = showUnlinked,
                                onClick = { showUnlinked = true },
                                text = { Text("Unlinked accounts (${loaded.unlinkedLegs.size})") },
                            )
                        }
                    }
                    if (showUnlinked) {
                        legGroups(
                            prefix = "unlinked-source",
                            groups = prepared.unlinked,
                            expanded = expanded,
                            leafIds = { listOf(it.transactionId) },
                            highlight = highlightedLeaf,
                            empty = "No transactions on unlinked wallets.",
                        ) { LegRow(it, accountNames, onOpenLeg) }
                    } else {
                        sectionHeading("near", "Near matches (${prepared.nearCount})") {
                            prepared.unstoredNear.let { BulkButton("Accept all", it.size, enabled = !writing) { storeMatches(it) } }
                        }
                        legGroups(
                            prefix = "near",
                            groups = prepared.near,
                            expanded = expanded,
                            leafIds = { match -> match.sourceLegs.map { it.transactionId } },
                            highlight = highlightedLeaf,
                            empty = "No near matches: every match agrees to the second.",
                            headerAction = { matches, _, _ ->
                                val pending = remember(matches) { matches.filter { it.matchId == null } }
                                BulkButton("Accept all", pending.size, enabled = !writing) { storeMatches(pending) }
                            },
                        ) { match ->
                            MatchRow(match, accountNames, onOpenLeg) {
                                TextButton(
                                    onClick = {
                                        write(
                                            "Matching",
                                        ) { importEngine.acceptMatches(listOf(match.memberTransactions())) }
                                    },
                                    enabled = !writing,
                                ) { Text("Accept") }
                            }
                        }
                        val onlyInSource = prepared.onlyInSource
                        sectionHeading("source-only", "Only in ${source.comparisonLabel} (${onlyInSource.size})") {
                            BulkButton("Ignore all", onlyInSource.size, enabled = !writing) { ignoreAll(loaded.movementsOf(onlyInSource)) }
                        }
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = hideUnknownAssets, onCheckedChange = { hideUnknownAssets = it })
                                Text("Hide assets Money Manager has never seen on the linked accounts (e.g. spam airdrops)")
                            }
                        }
                        legGroups(
                            prefix = "source-only",
                            groups = prepared.source,
                            expanded = expanded,
                            leafIds = { listOf(it.leg.transactionId) },
                            highlight = highlightedLeaf,
                            empty = "Everything ${source.name} has on the linked wallets is in Money Manager.",
                            headerAction = {
                                rows,
                                _,
                                _,
                                ->
                                BulkButton("Ignore all", rows.size, enabled = !writing) { ignoreAll(loaded.movementsOf(rows)) }
                            },
                        ) { unmatched ->
                            LegRow(unmatched.leg, accountNames, onOpenLeg) {
                                TextButton(
                                    onClick = { setIgnored(loaded.movementOf(unmatched), ignored = true) },
                                    enabled = !writing,
                                ) { Text("Ignore") }
                                TextButton(
                                    onClick = { pickerState.open(unmatched.pickerKey) },
                                    enabled = !writing,
                                ) { Text("Select matching transactions…") }
                            }
                        }
                        sectionHeading("money-manager-only", "Only in Money Manager (${loaded.missingInSource.size})")
                        legGroups(
                            prefix = "mm-only",
                            groups = prepared.real,
                            expanded = expanded,
                            leafIds = { listOf(it.leg.transactionId) },
                            highlight = highlightedLeaf,
                            empty = "Everything on the linked accounts in ${source.name}'s date range is in ${source.name}.",
                        ) { LegRow(it.leg, accountNames, onOpenLeg) }
                    }
                }

                ResultTab.MATCHED -> {
                    // Accepted and not-yet-accepted apart, so "Accept all" covers exactly the rows listed beneath it.
                    val accepted = prepared.accepted
                    val notAccepted = prepared.notAccepted
                    sectionHeading("not-accepted", "Not accepted (${notAccepted.size})") {
                        BulkButton("Accept all", notAccepted.size, enabled = !writing) { storeMatches(notAccepted) }
                    }
                    legGroups(
                        prefix = "not-accepted",
                        groups = prepared.notAcceptedGroups,
                        expanded = expanded,
                        leafIds = { match -> match.sourceLegs.map { it.transactionId } },
                        highlight = highlightedLeaf,
                        empty = "Nothing waiting to be accepted.",
                        headerAction = { items, _, _ ->
                            BulkButton("Accept all", items.size, enabled = !writing) { storeMatches(items) }
                        },
                    ) { match ->
                        MatchRow(match, accountNames, onOpenLeg) {
                            TextButton(
                                onClick = { write("Matching") { importEngine.acceptMatches(listOf(match.memberTransactions())) } },
                                enabled = !writing,
                            ) { Text("Accept") }
                        }
                    }
                    sectionHeading("accepted", "Accepted (${accepted.size})")
                    legGroups(
                        prefix = "accepted",
                        groups = prepared.acceptedGroups,
                        expanded = expanded,
                        leafIds = { match -> match.sourceLegs.map { it.transactionId } },
                        highlight = highlightedLeaf,
                        empty = "Nothing accepted yet.",
                    ) { match ->
                        val matchId = requireNotNull(match.matchId)
                        MatchRow(match, accountNames, onOpenLeg) {
                            TextButton(
                                onClick = { write("Unmatching") { importEngine.deleteRelationship(RelationshipId(matchId)) } },
                                enabled = !writing,
                            ) { Text("Unmatch") }
                        }
                    }
                    sectionHeading("ignored", "Ignored (${loaded.ignored.size})")
                    legGroups(
                        prefix = "ignored",
                        groups = prepared.ignored,
                        expanded = expanded,
                        leafIds = { listOf(it.leg.transactionId) },
                        highlight = highlightedLeaf,
                        empty = "No ${source.name} rows ignored.",
                    ) { ignored ->
                        LegRow(ignored.leg, accountNames, onOpenLeg) {
                            TextButton(
                                onClick = { setIgnored(listOf(ignored.leg), ignored = false) },
                                enabled = !writing,
                            ) { Text("Un-ignore") }
                        }
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
                    importEngine.acceptMatches(listOf((movement + selected).map { it.transactionRef } + feeId))
                    refreshViews()
                }
            },
            onOpenLeg = onOpenLeg,
            onOpenSource = onOpenSource,
            copyPlan = remember(movement, shadowAccounts, links, accountNames) { planCopy(movement, shadowAccounts, links, accountNames) },
            onCopy = { plan ->
                pickerState.close()
                write("Copying to Money Manager") {
                    importEngine.copyToMoneyManager(movement, plan)
                    refreshViews()
                }
            },
        )
    }
}

private val ReconciliationSource.comparisonLabel: String
    get() = if (name == "Money Manager") "imported Money Manager" else name

private fun LazyListScope.sectionHeading(
    key: String,
    text: String,
    action: (@Composable () -> Unit)? = null,
) {
    item(key = "heading-$key") {
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
                AssistChip(onClick = {}, label = { Text("Only in ${source.comparisonLabel}: $missingInMm") })
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
    linkError: String?,
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
            linkError?.let { LinkError(it) }
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

private fun LazyListScope.linkedWallets(
    source: ReconciliationSource,
    linked: List<ShadowAccount>,
    linksByWallet: Map<AccountId, List<AccountId>>,
    open: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean,
    accountNames: Map<AccountId, String>,
    counts: Map<AccountId, WalletCounts>,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    personRepository: PersonReadRepository,
    isReal: (Account) -> Boolean,
    linkErrors: Map<AccountId, String>,
    onSetLinks: (AccountId, Set<AccountId>) -> Unit,
) {
    if (linked.isEmpty()) return
    item(key = "linked-wallets-heading") {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp).clickable(onClick = onToggle)) {
            Text(
                "${if (open) "▾" else "▸"} Linked wallets (${linked.size})",
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
    if (!open) return
    items(linked, key = { "linked-wallet-${it.accountId.id}" }) { wallet ->
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
                        enabled = enabled,
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
                    enabled = enabled,
                )
            }
            linkErrors[wallet.accountId]?.let { LinkError(it) }
        }
    }
}

private fun <T> LazyListScope.legGroups(
    prefix: String,
    groups: List<WalletGroup<T>>,
    expanded: MutableMap<String, Boolean>,
    leafIds: (T) -> List<Long>,
    highlight: Long?,
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
                items(month.items.size, key = { leafKey(leafIds(month.items[it]), monthKey, it) }) {
                    val marked = highlight != null && highlight in leafIds(month.items[it])
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .then(
                                    if (marked) {
                                        Modifier
                                            .background(
                                                MaterialTheme.colorScheme.primaryContainer,
                                            ).testTag(HIGHLIGHTED_TAG)
                                    } else {
                                        Modifier
                                    },
                                ).padding(start = 32.dp),
                    ) { row(month.items[it]) }
                }
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

/** The bar and step shown while reconciling or applying a bulk action. */
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

/** Frames to wait for a row to appear in the list before giving up on scrolling to it. */
private const val SCROLL_ATTEMPTS = 30

/** Where a movement sits in the comparison: its [tab], the [prefix] of the list and the [wallet] and [month] groups holding it. */
private data class LeafLocation(
    val tab: ResultTab,
    val prefix: String,
    val wallet: AccountId,
    val month: String,
)

/** Finds [transactionId] among [result]'s lists, grouped as the screen groups them; null when it is in none (or hidden). */
private fun locateLeaf(
    result: ReconciliationResult,
    transactionId: Long,
    hideUnknownAssets: Boolean,
): LeafLocation? {
    fun LegMatch.holds() = sourceLegs.any { it.transactionId == transactionId }

    fun UnmatchedSourceLeg.holds() = leg.transactionId == transactionId
    return result.nearMatches.firstOrNull { it.holds() }?.let {
        LeafLocation(ResultTab.UNMATCHED, "near", it.sourceLeg.accountId, monthKey(it.sourceLeg.timestamp))
    } ?: result.missingInMm.firstOrNull { it.holds() && (!hideUnknownAssets || !it.assetUnknownToMm) }?.let {
        LeafLocation(ResultTab.UNMATCHED, "source-only", it.walletAccountId, monthKey(it.leg.timestamp))
    } ?: result.unlinkedLegs.firstOrNull { it.transactionId == transactionId }?.let {
        LeafLocation(ResultTab.UNMATCHED, "unlinked-source", it.accountId, monthKey(it.timestamp))
    } ?: result.matched.firstOrNull { it.holds() }?.let {
        val prefix = if (it.matchId == null) "not-accepted" else "accepted"
        LeafLocation(ResultTab.MATCHED, prefix, it.sourceLeg.accountId, monthKey(it.sourceLeg.timestamp))
    } ?: result.ignored.firstOrNull { it.holds() }?.let {
        LeafLocation(ResultTab.MATCHED, "ignored", it.walletAccountId, monthKey(it.leg.timestamp))
    }
}

/** The key of a row of a list: the transactions it shows, then where it sits, so it is unique and can be searched for. */
private fun leafKey(
    transactionIds: List<Long>,
    month: String,
    index: Int,
): String = "leaf:${transactionIds.joinToString("+")}:$month:$index"

private fun leafHolds(
    key: Any,
    transactionId: Long,
): Boolean {
    val ids =
        (key as? String)
            ?.takeIf { it.startsWith("leaf:") }
            ?.split(':')
            ?.getOrNull(1)
            ?.split('+')
    return ids?.contains(transactionId.toString()) == true
}

/** Passes every item through to [delegate] and notes the index of each keyed one, so a row can be scrolled to by its key. */
private class IndexingScope(
    private val delegate: LazyListScope,
    private val indexByKey: MutableMap<Any, Int>,
) : LazyListScope by delegate {
    private var next = 0

    init {
        indexByKey.clear()
    }

    override fun item(
        key: Any?,
        contentType: Any?,
        content: @Composable LazyItemScope.() -> Unit,
    ) {
        key?.let { indexByKey[it] = next }
        next++
        delegate.item(key, contentType, content)
    }

    override fun items(
        count: Int,
        key: ((Int) -> Any)?,
        contentType: (Int) -> Any?,
        itemContent: @Composable LazyItemScope.(Int) -> Unit,
    ) {
        if (key != null) repeat(count) { indexByKey[key(it)] = next + it }
        next += count
        delegate.items(count, key, contentType, itemContent)
    }
}

/** Test tag of the row a link named, which is marked in the list. */
internal const val HIGHLIGHTED_TAG = "reconciliation-highlighted"

@Composable
private fun LinkError(message: String) {
    Text(
        message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(vertical = 4.dp).testTag("link-error"),
    )
}

package com.moneymanager.tui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.layout.padding
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.moneymanager.database.di.AppServices
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountRow
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.SourceRecord
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.toSourceType
import com.moneymanager.presenter.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.presenter.transactions.TransactionRange
import com.moneymanager.presenter.transactions.TransactionsEvent
import com.moneymanager.presenter.transactions.TransactionsModel
import com.moneymanager.presenter.transactions.presentTransactions
import com.moneymanager.query.AccountSnapshot
import com.moneymanager.query.LinkedTransfer
import com.moneymanager.query.TransactionDetail
import com.moneymanager.query.TransactionDetailQuery
import com.moneymanager.query.TransactionQuery
import com.moneymanager.query.counterpartyId
import com.moneymanager.query.parseDateBound
import com.moneymanager.terminal.fit
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** The rows above the list: title, range and status, and the column headings. */
private const val HEADER_ROWS = 3

/** How close to either end of the loaded rows the cursor gets before the next page loads. */
private const val PAGE_AHEAD = 3

/** Below this width the detail replaces the list instead of sitting beside it. */
private const val SIDE_BY_SIDE_WIDTH = 140

/**
 * One account's transactions, newest first, with their running balances, paged in both directions as the cursor
 * moves, with a date range, a jump to a date, and the selected row's detail (legs, attributes, linked transfers
 * and where it came from). The paging state is the shared transactions presenter's.
 */
val TransactionsScreen =
    TuiScreen(
        title = "Transactions",
        bindings =
            listOf(
                KeyBinding("a", "Pick the account"),
                KeyBinding("f", "Set the date range (YYYY-MM-DD or an ISO-8601 instant)"),
                KeyBinding("x", "Clear the date range"),
                KeyBinding("g", "Go to a date"),
                KeyBinding("Enter", "Show or hide the selected row's detail"),
                KeyBinding("n  l", "Detail: highlight the next linked transfer / go to it"),
                KeyBinding("r", "Refresh balances and reload"),
                KeyBinding("↑  ↓  PgUp  PgDn", "Move through the list (pages load as you go)"),
                KeyBinding("Home  End", "Newest row / last loaded row"),
            ),
    ) { scope ->
        val services = scope.session.services
        val query = remember(services) { TransactionQuery(services.transactions.transactionRepository) }
        val details = remember(services) { services.transactionDetailQuery() }
        val snapshots = remember(services) { services.accountQuery().snapshot() }
        val snapshot by snapshots.collectAsStateWithSchemaErrorHandling(initial = null)
        val visibleRows = (scope.height - HEADER_ROWS - 1).coerceAtLeast(1)
        val model =
            presentTransactions(
                query = query,
                details = details,
                accountId = scope.browsedAccount,
                // Two screens a page, so a page load is rarely in view.
                pageSize = (visibleRows * 2).coerceAtLeast(MIN_PAGE_SIZE),
                refreshViews = { services.imports.maintenance.refreshMaterializedViews() },
            )
        TransactionsView(model, snapshot, scope, visibleRows)
    }

private const val MIN_PAGE_SIZE = 20

@Composable
private fun TransactionsView(
    model: TransactionsModel,
    snapshot: AccountSnapshot?,
    scope: TuiScreenScope,
    visibleRows: Int,
) {
    val width = scope.width
    val inner = (width - 2).coerceAtLeast(0)
    val zone = remember { TimeZone.currentSystemDefault() }
    val coroutines = rememberCoroutineScope()
    val names = remember(snapshot) { snapshot?.accounts.orEmpty().associate { it.account.id to it.account.name } }
    var selected by remember { mutableIntStateOf(0) }
    var selectedId by remember { mutableStateOf<TransactionId?>(null) }
    // The row at the top of the view, by id, so prepending newer rows doesn't move what's on screen.
    var topId by remember { mutableStateOf<TransactionId?>(null) }
    var detailOpen by remember { mutableStateOf(false) }
    var link by remember { mutableIntStateOf(0) }
    var picker by remember { mutableStateOf<OpenPicker<AccountId>?>(null) }

    val rows = model.rows
    val ids = rows.map { it.transactionId }
    val last = (rows.size - 1).coerceAtLeast(0)

    // Read from the state each time, never kept from the last composition: keys that arrive together (fast typing, a
    // paste) are handled before the screen recomposes, and each must see where the one before it moved.
    fun currentIndex() = followSelection(ids, selectedId, selected)

    fun firstShown(index: Int) = ids.indexOf(topId).coerceAtLeast(0).coerceIn((index - visibleRows + 1).coerceAtLeast(0), index)

    val current = currentIndex()
    val first = firstShown(current)
    val currentId = ids.getOrNull(current)

    // A new account, range or jump starts at the top, or on the row the jump landed on.
    LaunchedEffect(model.loaded, model.focusId) {
        if (!model.loaded) {
            selectedId = null
            selected = 0
            topId = null
        } else {
            model.focusId?.let { selectedId = it }
        }
    }
    LaunchedEffect(detailOpen, currentId) {
        link = 0
        model.eventSink(TransactionsEvent.Select(if (detailOpen) currentId else null))
    }

    fun select(index: Int) {
        val shownFrom = firstShown(currentIndex())
        selected = index.coerceIn(0, last)
        selectedId = ids.getOrNull(selected)
        topId = ids.getOrNull(shownFrom.coerceIn((selected - visibleRows + 1).coerceAtLeast(0), selected))
        if (selected >= rows.size - PAGE_AHEAD && model.hasOlder) model.eventSink(TransactionsEvent.LoadOlder)
        if (selected < PAGE_AHEAD && model.hasNewer) model.eventSink(TransactionsEvent.LoadNewer)
    }

    fun ask(block: suspend () -> Unit) {
        coroutines.launch { block() }
    }

    fun askRange() =
        ask {
            val from =
                scope.prompter.text(
                    "From (blank for the earliest)",
                    model.range.from
                        ?.toString()
                        .orEmpty(),
                ) ?: return@ask
            val to =
                scope.prompter.text(
                    "To, inclusive (blank for the latest)",
                    model.range.to
                        ?.toString()
                        .orEmpty(),
                ) ?: return@ask
            try {
                val range =
                    TransactionRange(
                        from = from.takeIf { it.isNotBlank() }?.let { parseDateBound(it, zone, through = false) },
                        to = to.takeIf { it.isNotBlank() }?.let { parseDateBound(it, zone, through = true) },
                    )
                model.eventSink(TransactionsEvent.SetRange(range))
            } catch (invalid: IllegalArgumentException) {
                scope.prompter.error(invalid.message.orEmpty())
            }
        }

    fun askJump() =
        ask {
            val date = scope.prompter.text("Go to (YYYY-MM-DD or an ISO-8601 instant)", "") ?: return@ask
            if (date.isBlank()) return@ask
            try {
                model.eventSink(TransactionsEvent.JumpTo(parseDateBound(date, zone, through = true)))
            } catch (invalid: IllegalArgumentException) {
                scope.prompter.error(invalid.message.orEmpty())
            }
        }

    fun follow(linked: LinkedTransfer) {
        val transfer = linked.transfer
        val account = model.accountId
        val target =
            if (account == transfer.sourceAccountId || account == transfer.targetAccountId) account else transfer.sourceAccountId
        model.eventSink(TransactionsEvent.ShowTransfer(target, transfer.id))
        scope.openAccount(target)
    }

    fun key(event: KeyEvent): Boolean {
        val open = picker
        if (open != null) {
            if (!open.handle(event, multiSelectRows(scope.height))) picker = null
            return true
        }
        val links = model.detail?.links.orEmpty()
        val at = currentIndex()
        when (event.key) {
            "a" -> {
                picker =
                    OpenPicker(MultiSelect("Account", accountChoices(snapshot), setOfNotNull(model.accountId), single = true)) {
                        it.firstOrNull()?.let(scope.openAccount)
                    }
            }

            "f" -> {
                askRange()
            }

            "x" -> {
                model.eventSink(TransactionsEvent.SetRange(TransactionRange()))
            }

            "g" -> {
                askJump()
            }

            "r" -> {
                model.eventSink(TransactionsEvent.Refresh)
            }

            "Enter" -> {
                detailOpen = !detailOpen && ids.getOrNull(at) != null
            }

            "Escape" -> {
                if (detailOpen) detailOpen = false else return false
            }

            "n" -> {
                if (detailOpen && links.isNotEmpty()) link = (link + 1) % links.size
            }

            "l" -> {
                links.getOrNull(link)?.takeIf { detailOpen }?.let(::follow)
            }

            "ArrowUp" -> {
                select(at - 1)
            }

            "ArrowDown" -> {
                select(at + 1)
            }

            "PageUp" -> {
                select(at - visibleRows)
            }

            "PageDown" -> {
                select(at + visibleRows)
            }

            "Home" -> {
                if (model.hasNewer) model.eventSink(TransactionsEvent.SetRange(model.range)) else select(0)
            }

            "End" -> {
                select(last)
            }

            else -> {
                return false
            }
        }
        return true
    }

    val sideBySide = width >= SIDE_BY_SIDE_WIDTH
    val listWidth = if (detailOpen && sideBySide) inner * 3 / 5 else inner
    val detailWidth = if (sideBySide) inner - listWidth - 1 else inner
    Box(modifier = Modifier.onKeyEvent(::key)) {
        Column(modifier = Modifier.padding(horizontal = 1)) {
            Text(fit(title(model, names), inner), textStyle = TextStyle.Bold)
            val error = model.error
            if (error !=
                null
            ) {
                Text(fit(error, inner), color = Color.Red)
            } else {
                Text(fit(status(model, zone), inner), textStyle = TextStyle.Dim)
            }
            if (detailOpen && !sideBySide) {
                Text("")
                DetailPane(model.detail, names, zone, link, inner, visibleRows)
            } else {
                Row {
                    Column {
                        Text(
                            fit(columns("Date", "Description", "Counterparty", "Amount", "Balance", listWidth), listWidth),
                            textStyle = TextStyle.Dim,
                        )
                        when {
                            model.accountId == null -> Text(fit("Pick an account with a, or press Enter on one in Accounts.", listWidth))
                            model.loaded && rows.isEmpty() -> Text(fit("No transactions here.", listWidth))
                            !model.loaded -> Text(fit("Loading…", listWidth), textStyle = TextStyle.Dim)
                        }
                        rows.drop(first).take(visibleRows).forEachIndexed { offset, row ->
                            val style =
                                when {
                                    first + offset == current -> TextStyle.Invert
                                    row.isExcluded -> TextStyle.Dim
                                    else -> TextStyle.Unspecified
                                }
                            Text(fit(row.line(names, zone, listWidth), listWidth), textStyle = style)
                        }
                    }
                    if (detailOpen) {
                        Text(" ")
                        Column { DetailPane(model.detail, names, zone, link, detailWidth, visibleRows + 1) }
                    }
                }
            }
        }
        picker?.let { MultiSelectDialog(it.select, width, scope.height) }
    }
}

@Composable
private fun DetailPane(
    detail: TransactionDetail?,
    names: Map<AccountId, String>,
    zone: TimeZone,
    highlighted: Int,
    width: Int,
    rows: Int,
) {
    if (detail == null) {
        Text(fit("Loading…", width), textStyle = TextStyle.Dim)
        return
    }
    detailLines(detail, names, zone, highlighted).take(rows).forEach { (text, style) -> Text(fit(text, width), textStyle = style) }
}

/** The detail as lines, each with its style: headings bold, the highlighted link inverted. */
internal fun detailLines(
    detail: TransactionDetail,
    names: Map<AccountId, String>,
    zone: TimeZone,
    highlighted: Int,
): List<Pair<String, TextStyle>> {
    val row = detail.row
    val lines = mutableListOf<Pair<String, TextStyle>>()

    fun heading(text: String) = lines.add(text to TextStyle.Bold)

    fun line(
        text: String,
        style: TextStyle = TextStyle.Unspecified,
    ) = lines.add("  $text" to style)

    heading("${row.kind.name.lowercase().replaceFirstChar { it.uppercase() }} ${row.transactionId} · ${row.timestamp.local(zone)}")
    line(row.description)
    val flags = listOfNotNull("excluded".takeIf { row.isExcluded }, "reconciled".takeIf { row.isReconciled })
    if (flags.isNotEmpty()) line(flags.joinToString(" · "), TextStyle.Dim)
    heading("Legs")
    detail.legs.forEach { line("${names.nameOf(it.from)} → ${names.nameOf(it.to)}  ${it.amount.text()}") }
    heading("Attributes")
    if (detail.attributes.isEmpty()) line("none", TextStyle.Dim)
    detail.attributes.forEach { line("${it.name}: ${it.value}") }
    heading("Linked transfers")
    if (detail.links.isEmpty()) line("none", TextStyle.Dim)
    detail.links.forEachIndexed { index, linked ->
        val transfer = linked.transfer
        val arrow = if (linked.outgoing) "→" else "←"
        val text =
            "${linked.relationship} $arrow ${transfer.id} ${transfer.timestamp.local(zone)} " +
                "${names.nameOf(transfer.sourceAccountId)} → ${names.nameOf(transfer.targetAccountId)} ${transfer.amount.text()}"
        line(text, if (index == highlighted) TextStyle.Invert else TextStyle.Unspecified)
    }
    heading("Sources")
    if (detail.sources.isEmpty()) line("none", TextStyle.Dim)
    detail.sources.forEach { line(it.describe(zone)) }
    return lines
}

private fun SourceRecord.describe(zone: TimeZone): String =
    listOfNotNull(
        "revision $revisionId",
        source
            .toSourceType()
            .name
            .lowercase()
            .replace('_', ' '),
        fileName,
        deviceInfo?.describe(),
        createdAt.local(zone),
    ).joinToString(" · ")

private fun DeviceInfo.describe(): String =
    when (this) {
        is DeviceInfo.Host -> "$machineName ($osName)"
        is DeviceInfo.Android -> "$deviceMake $deviceModel"
    }

private fun title(
    model: TransactionsModel,
    names: Map<AccountId, String>,
): String {
    val account = model.accountId ?: return "Transactions"
    val more = listOfNotNull("newer above".takeIf { model.hasNewer }, "older below".takeIf { model.hasOlder })
    val count = if (model.loaded) "${model.rows.size} rows loaded" else "loading…"
    return (listOf("Transactions · ${names.nameOf(account)}", count) + more).joinToString(" · ")
}

private fun status(
    model: TransactionsModel,
    zone: TimeZone,
): String {
    val from = model.range.from?.local(zone) ?: "earliest"
    val to = model.range.to?.let { "before ${it.local(zone)}" } ?: "latest"
    val range = if (model.range == TransactionRange()) "all dates" else "$from – $to"
    return "Range: $range" + if (model.extending) " · loading more…" else ""
}

private fun AccountRow.line(
    names: Map<AccountId, String>,
    zone: TimeZone,
    width: Int,
): String =
    columns(
        timestamp.local(zone),
        description,
        names.nameOf(counterpartyId),
        transactionAmount.text(),
        runningBalance.text(),
        width,
    )

/** Date, description, counterparty, amount and balance; the amounts right-aligned in fixed columns. */
private fun columns(
    date: String,
    description: String,
    counterparty: String,
    amount: String,
    balance: String,
    width: Int,
): String {
    val dateWidth = 17
    val amountWidth = (width / 6).coerceAtLeast(10)
    val rest = (width - dateWidth - 2 * amountWidth).coerceAtLeast(2)
    val counterpartyWidth = rest * 2 / 5
    val descriptionWidth = rest - counterpartyWidth
    return fit(date, dateWidth - 1).padEnd(dateWidth) +
        fit(description, descriptionWidth - 1).padEnd(descriptionWidth) +
        fit(counterparty, counterpartyWidth - 1).padEnd(counterpartyWidth) +
        fit(amount, amountWidth - 1).padStart(amountWidth - 1) + " " +
        fit(balance, amountWidth - 1).padStart(amountWidth - 1)
}

private fun Money.text() = "${toDisplayValue()} ${asset.code}"

private fun Map<AccountId, String>.nameOf(id: AccountId) = this[id] ?: "#$id"

/** Minutes are enough to tell rows apart on screen; the detail pane and the CLI's JSON have the rest. */
private fun Instant.local(zone: TimeZone): String {
    val local = toLocalDateTime(zone)
    return "${local.date} ${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
}

/** Every account to pick from, by name, shadow ones marked. */
private fun accountChoices(snapshot: AccountSnapshot?): List<Pair<AccountId, String>> =
    snapshot
        ?.accounts
        .orEmpty()
        .sortedBy { it.account.name.lowercase() }
        .map { it.account.id to it.account.name + if (it.isShadow) " (shadow)" else "" }

/** The detail query over the session's read services. */
internal fun AppServices.transactionDetailQuery(): TransactionDetailQuery =
    TransactionDetailQuery(
        transactionRepository = transactions.transactionRepository,
        tradeRepository = transactions.tradeRepository,
        relationshipRepository = transactions.transferRelationshipRepository,
        sourceRepository = transactions.transferSourceRepository,
    )

package com.moneymanager.tui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.moneymanager.presenter.accounts.AccountsEvent
import com.moneymanager.presenter.accounts.AccountsModel
import com.moneymanager.presenter.accounts.presentAccounts
import com.moneymanager.query.AccountQuery
import com.moneymanager.query.AccountWithBalances
import com.moneymanager.terminal.fit
import kotlin.math.abs

/** The rows above the list: title, two filter lines, the filter's error or a blank line, and the column headings. */
private const val HEADER_ROWS = 5

/**
 * Accounts with their owners and balances, filtered live and optionally grouped by owner. The filter state is the
 * shared accounts presenter's, so it keeps what the desktop Accounts screen keeps.
 */
val AccountsScreen =
    TuiScreen(
        title = "Accounts",
        bindings =
            listOf(
                KeyBinding("/", "Edit the name filter (Enter or Esc to stop)"),
                KeyBinding("r", "Name filter: plain text or regex"),
                KeyBinding("p", "Pick owners"),
                KeyBinding("a", "Pick assets"),
                KeyBinding("s", "Show or hide reconciliation (shadow) accounts"),
                KeyBinding("z", "Show only non-zero balances"),
                KeyBinding("g", "Group by owner, or list by name"),
                KeyBinding("Enter", "Open the account's transactions"),
                KeyBinding("↑  ↓  PgUp  PgDn", "Move through the list"),
            ),
    ) { scope ->
        val services = scope.session.services
        val snapshots = remember(services) { services.accountQuery().snapshot() }
        AccountsView(presentAccounts(snapshots), scope.width, scope.height, scope.openAccount)
    }

/** A row of the list: an owner's heading when grouped, or an account. */
private sealed interface ListLine {
    data class Heading(
        val text: String,
    ) : ListLine

    data class Entry(
        val account: AccountWithBalances,
    ) : ListLine
}

@Composable
private fun AccountsView(
    model: AccountsModel,
    width: Int,
    height: Int,
    openAccount: (AccountId) -> Unit,
) {
    val inner = (width - 2).coerceAtLeast(0)
    val visibleRows = (height - HEADER_ROWS - 1).coerceAtLeast(1)
    val editor = remember { LineEditor(model.filter.nameText) }
    var editing by remember { mutableStateOf(false) }
    var grouped by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<OpenPicker<*>?>(null) }
    // The selection counts accounts, not lines: in a grouped list the same account can be under two owners.
    var selected by remember { mutableIntStateOf(0) }
    // Which account that was, so the selection follows it when the list changes under it (an import, a filter).
    var selectedId by remember { mutableStateOf<AccountId?>(null) }
    var top by remember { mutableIntStateOf(0) }
    val count = model.displayed.size

    val lines =
        if (grouped) {
            model.byOwner.flatMap { group ->
                val owner = group.owner?.fullName ?: "No owner"
                listOf(ListLine.Heading("$owner · ${group.accounts.size}")) + group.accounts.map { ListLine.Entry(it) }
            }
        } else {
            model.displayed.map { ListLine.Entry(it) }
        }
    val entryLines = lines.indices.filter { lines[it] is ListLine.Entry }
    val entryIds = entryLines.map { (lines[it] as ListLine.Entry).account.account.id }

    LaunchedEffect(editor.text) { model.eventSink(AccountsEvent.SetName(editor.text)) }
    // The list can shrink under the selection (a filter, a deleted account), so clamp what's drawn.
    val last = (entryLines.size - 1).coerceAtLeast(0)

    // Read from the state each time, never kept from the last composition: keys that arrive together (fast typing, a
    // paste) are handled before the screen recomposes, and each must see where the one before it moved.
    fun currentIndex() = followSelection(entryIds, selectedId, selected)

    fun lineOf(index: Int) = entryLines.getOrElse(index) { 0 }

    fun firstShown(line: Int) = top.coerceIn((line - visibleRows + 1).coerceAtLeast(0), line)

    val current = currentIndex()
    val currentLine = lineOf(current)
    val first = firstShown(currentLine)

    fun select(index: Int) {
        val shownFrom = firstShown(lineOf(currentIndex()))
        selected = index.coerceIn(0, last)
        selectedId = entryIds.getOrNull(selected)
        val line = lineOf(selected)
        top = shownFrom.coerceIn((line - visibleRows + 1).coerceAtLeast(0), line)
    }

    fun key(event: KeyEvent): Boolean {
        val at = currentIndex()
        val open = picker
        if (open != null) {
            if (!open.handle(event, multiSelectRows(height))) picker = null
            // Every key belongs to an open picker, even the shell's own.
            return true
        }
        if (editing) {
            if (event.key == "Enter" || event.key == "Escape") editing = false else editor.handle(event)
            // Every other key is text while the filter is being edited, even the shell's own.
            return true
        }
        when (event.key) {
            "/" -> editing = true
            "r" -> model.eventSink(AccountsEvent.SetNameIsRegex(!model.filter.nameIsRegex))
            "p" ->
                picker =
                    OpenPicker(MultiSelect("Owners", model.people.map { it.id to it.fullName }, model.filter.ownerIds)) {
                        model.eventSink(AccountsEvent.SetOwners(it))
                    }
            "a" ->
                picker =
                    OpenPicker(MultiSelect("Assets", model.availableAssets.map { it.id to it.code }, model.filter.assetIds)) {
                        model.eventSink(AccountsEvent.SetAssets(it))
                    }
            "s" -> model.eventSink(AccountsEvent.SetIncludeShadow(!model.filter.includeShadow))
            "z" -> model.eventSink(AccountsEvent.SetNonZeroOnly(!model.filter.nonZeroOnly))
            "g" -> grouped = !grouped
            "Enter" -> (lines.getOrNull(lineOf(at)) as? ListLine.Entry)?.let { openAccount(it.account.account.id) }
            "ArrowUp" -> select(at - 1)
            "ArrowDown" -> select(at + 1)
            "PageUp" -> select(at - visibleRows)
            "PageDown" -> select(at + visibleRows)
            else -> return false
        }
        return true
    }

    Box(modifier = Modifier.onKeyEvent(::key)) {
        Column(modifier = Modifier.padding(horizontal = 1)) {
            val shown = if (model.loaded) "$count of ${model.all.size}" else "loading…"
            Text(fit("Accounts · $shown", inner), textStyle = TextStyle.Bold)
            NameLine(model, editor, editing, inner)
            Text(fit(flags(model, grouped), inner), textStyle = TextStyle.Dim)
            val error = model.nameError
            if (error != null) Text(fit(error, inner), color = Color.Red) else Text("")
            Text(fit(columns("Name", "Owners", "Balances", inner), inner), textStyle = TextStyle.Dim)
            if (model.loaded && count == 0) {
                Text(fit(if (model.all.isEmpty()) "No accounts yet." else "No accounts match the filters.", inner))
            }
            lines.drop(first).take(visibleRows).forEachIndexed { offset, line ->
                when (line) {
                    is ListLine.Heading -> Text(fit(line.text, inner), textStyle = TextStyle.Bold)
                    is ListLine.Entry -> {
                        val text = fit(line.account.line(inner), inner)
                        Text(text, textStyle = if (first + offset == currentLine) TextStyle.Invert else TextStyle.Unspecified)
                    }
                }
            }
        }
        picker?.let { MultiSelectDialog(it.select, width, height) }
    }
}

@Composable
private fun NameLine(
    model: AccountsModel,
    editor: LineEditor,
    editing: Boolean,
    width: Int,
) {
    val label = if (model.filter.nameIsRegex) "Regex: " else "Name: "
    val fieldWidth = (width - label.length).coerceAtLeast(1)
    Row {
        Text(label, textStyle = TextStyle.Dim)
        if (editing) {
            LineField(editor, fieldWidth)
        } else {
            Text(fit(editor.text.ifEmpty { "(press / to filter)" }, fieldWidth))
        }
    }
}

/** Every filter but the name, and how the list is laid out. */
private fun flags(
    model: AccountsModel,
    grouped: Boolean,
): String {
    val filter = model.filter
    val owners =
        model.people
            .filter { it.id in filter.ownerIds }
            .joinToString { it.fullName }
            .ifEmpty { "any" }
    val assets =
        model.availableAssets
            .filter { it.id in filter.assetIds }
            .joinToString { it.code }
            .ifEmpty { "any" }
    val layout = if (grouped) "grouped by owner" else "by name"
    return "Owners: $owners · Assets: $assets · shadow ${onOff(filter.includeShadow)} · " +
        "non-zero ${onOff(filter.nonZeroOnly)} · $layout"
}

private fun onOff(on: Boolean) = if (on) "on" else "off"

private fun AccountWithBalances.line(width: Int): String =
    columns(
        account.name + if (isShadow) " (shadow)" else "",
        owners.joinToString { it.fullName },
        balances.joinToString { "${it.toDisplayValue()} ${it.asset.code}" },
        width,
    )

/** Name, owners and balances in columns of about 40%, 25% and the rest of [width]. */
private fun columns(
    name: String,
    owners: String,
    balances: String,
    width: Int,
): String {
    val nameWidth = width * 2 / 5
    val ownersWidth = width / 4
    return fit(name, nameWidth - 1).padEnd(nameWidth) + fit(owners, ownersWidth - 1).padEnd(ownersWidth) + balances
}

/**
 * Where the selection is in a list of [entries]: on [selectedId] when it's still listed (the occurrence nearest
 * [previous] when it's listed twice), otherwise at [previous], clamped to the list.
 */
internal fun <T> followSelection(
    entries: List<T>,
    selectedId: T?,
    previous: Int,
): Int =
    entries.indices.filter { entries[it] == selectedId }.minByOrNull { abs(it - previous) }
        ?: previous.coerceIn(0, (entries.size - 1).coerceAtLeast(0))

/** The account query over the session's read services, as `mm accounts` builds it. */
internal fun AppServices.accountQuery(): AccountQuery =
    AccountQuery(
        accountRepository = accounts.accountRepository,
        accountAttributeRepository = accounts.accountAttributeRepository,
        transactionRepository = transactions.transactionRepository,
        personRepository = people.personRepository,
        personAccountOwnershipRepository = people.personAccountOwnershipRepository,
    )

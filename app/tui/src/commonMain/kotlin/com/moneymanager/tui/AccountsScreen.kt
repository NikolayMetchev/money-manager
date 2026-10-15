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
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.moneymanager.database.di.AppServices
import com.moneymanager.presenter.accounts.AccountsEvent
import com.moneymanager.presenter.accounts.AccountsModel
import com.moneymanager.presenter.accounts.presentAccounts
import com.moneymanager.query.AccountQuery
import com.moneymanager.query.AccountWithBalances
import com.moneymanager.terminal.fit

/** The rows above the list: title, filter line, and the filter's error or a blank line. */
private const val HEADER_ROWS = 4

/**
 * Accounts with their owners and balances, filtered live. The filter state is the shared accounts presenter's,
 * so it keeps what the desktop Accounts screen keeps. Owner and asset pickers come with #943.
 */
val AccountsScreen =
    TuiScreen(
        title = "Accounts",
        bindings =
            listOf(
                KeyBinding("/", "Edit the name filter (Enter or Esc to stop)"),
                KeyBinding("r", "Name filter: plain text or regex"),
                KeyBinding("s", "Show or hide reconciliation (shadow) accounts"),
                KeyBinding("z", "Show only non-zero balances"),
                KeyBinding("↑  ↓  PgUp  PgDn", "Move through the list"),
            ),
    ) { scope ->
        val services = scope.session.services
        val snapshots = remember(services) { services.accountQuery().snapshot() }
        AccountsView(presentAccounts(snapshots), scope.width, scope.height)
    }

@Composable
private fun AccountsView(
    model: AccountsModel,
    width: Int,
    height: Int,
) {
    val inner = (width - 2).coerceAtLeast(0)
    val visibleRows = (height - HEADER_ROWS - 1).coerceAtLeast(1)
    val editor = remember { LineEditor(model.filter.nameText) }
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableIntStateOf(0) }
    var top by remember { mutableIntStateOf(0) }
    val count = model.displayed.size

    LaunchedEffect(editor.text) { model.eventSink(AccountsEvent.SetName(editor.text)) }
    // The list can shrink under the selection (a filter, a deleted account), so clamp what's drawn.
    val last = (count - 1).coerceAtLeast(0)
    val current = selected.coerceIn(0, last)
    val first = top.coerceIn((current - visibleRows + 1).coerceAtLeast(0), current)

    fun select(index: Int) {
        selected = index.coerceIn(0, last)
        top = first.coerceIn((selected - visibleRows + 1).coerceAtLeast(0), selected)
    }

    fun key(event: KeyEvent): Boolean {
        if (editing) {
            if (event.key == "Enter" || event.key == "Escape") editing = false else editor.handle(event)
            // Every other key is text while the filter is being edited, even the shell's own.
            return true
        }
        when (event.key) {
            "/" -> editing = true
            "r" -> model.eventSink(AccountsEvent.SetNameIsRegex(!model.filter.nameIsRegex))
            "s" -> model.eventSink(AccountsEvent.SetIncludeShadow(!model.filter.includeShadow))
            "z" -> model.eventSink(AccountsEvent.SetNonZeroOnly(!model.filter.nonZeroOnly))
            "ArrowUp" -> select(current - 1)
            "ArrowDown" -> select(current + 1)
            "PageUp" -> select(current - visibleRows)
            "PageDown" -> select(current + visibleRows)
            else -> return false
        }
        return true
    }

    Column(modifier = Modifier.padding(horizontal = 1).onKeyEvent(::key)) {
        val shown = if (model.loaded) "$count of ${model.all.size}" else "loading…"
        Text(fit("Accounts · $shown", inner), textStyle = TextStyle.Bold)
        FilterLine(model, editor, editing, inner)
        val error = model.nameError
        if (error != null) Text(fit(error, inner), color = Color.Red) else Text("")
        Text(fit(columns("Name", "Owners", "Balances", inner), inner), textStyle = TextStyle.Dim)
        if (model.loaded && count == 0) {
            Text(fit(if (model.all.isEmpty()) "No accounts yet." else "No accounts match the filters.", inner))
        }
        model.displayed.drop(first).take(visibleRows).forEachIndexed { offset, entry ->
            val line = fit(entry.line(inner), inner)
            Text(line, textStyle = if (first + offset == current) TextStyle.Invert else TextStyle.Unspecified)
        }
    }
}

@Composable
private fun FilterLine(
    model: AccountsModel,
    editor: LineEditor,
    editing: Boolean,
    width: Int,
) {
    val filter = model.filter
    val label = if (filter.nameIsRegex) "Regex: " else "Name: "
    val flags = " · shadow ${onOff(filter.includeShadow)} · non-zero ${onOff(filter.nonZeroOnly)}"
    val fieldWidth = (width - label.length - flags.length).coerceAtLeast(1)
    Row {
        Text(label, textStyle = TextStyle.Dim)
        if (editing) {
            LineField(editor, fieldWidth)
        } else {
            Text(fit(editor.text.ifEmpty { "(press / to filter)" }.padEnd(fieldWidth), fieldWidth))
        }
        Text(fit(flags, width - label.length - fieldWidth), textStyle = TextStyle.Dim)
    }
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

/** The account query over the session's read services, as `mm accounts` builds it. */
internal fun AppServices.accountQuery(): AccountQuery =
    AccountQuery(
        accountRepository = accounts.accountRepository,
        accountAttributeRepository = accounts.accountAttributeRepository,
        transactionRepository = transactions.transactionRepository,
        personRepository = people.personRepository,
        personAccountOwnershipRepository = people.personAccountOwnershipRepository,
    )

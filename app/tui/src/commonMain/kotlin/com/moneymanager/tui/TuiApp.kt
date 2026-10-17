package com.moneymanager.tui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.layout.drawBehind
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.layout.onPreviewKeyEvent
import com.jakewharton.mosaic.layout.padding
import com.jakewharton.mosaic.layout.size
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Alignment
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.credentialvault.VaultState
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.files.expandTilde
import com.moneymanager.terminal.fit

/** The keys that work everywhere, as the help overlay lists them. */
val GLOBAL_BINDINGS: List<KeyBinding> =
    listOf(
        KeyBinding("Tab  →  ←", "Next / previous tab"),
        KeyBinding("1 … 9", "Go to a tab"),
        KeyBinding("o", "Open or create another database"),
        KeyBinding("Esc", "Dismiss the message"),
        KeyBinding("?", "Show or hide this help"),
        KeyBinding("q  Ctrl-C", "Quit (Ctrl-C twice: quit at once)"),
    )

/**
 * The TUI shell: a tab bar over the current [screens] entry, a footer with the open database and vault state,
 * and the overlays that take every key while they're open (prompts, the database chooser, help). It lays out
 * to the terminal's current size, so a resize just redraws.
 */
@Composable
fun TuiApp(
    controller: TuiController,
    screens: List<TuiScreen>,
    vault: CredentialVault,
) {
    val size = LocalTerminalState.current.size
    val vaultState by vault.state.collectAsState()
    var selected by remember { mutableIntStateOf(0) }
    var help by remember { mutableStateOf(false) }
    val session = controller.session
    val work = controller.work
    // Kept by the shell so the Accounts tab can open an account in the Transactions tab; another database forgets it.
    var browsedAccount by remember(session) { mutableStateOf<AccountId?>(null) }
    val prompter = controller.prompter
    val choice = controller.choice
    val busy = controller.busy
    val width = size.columns
    val bodyHeight = (size.rows - 2).coerceAtLeast(0)

    // Reads the controller's state when the key arrives, not as the last composition saw it: keys that arrive together
    // (fast typing, a paste) are handled before the screen recomposes.
    fun preview(event: KeyEvent): Boolean {
        val pending = controller.choice
        return when {
            event.ctrl && event.key == "c" -> {
                controller.interrupt()
                true
            }
            prompter.handleKey(event) -> true
            help -> {
                if (event.key == "?" || event.key == "Escape" || event.key == "q") help = false
                true
            }
            pending != null -> {
                handleChoiceKey(controller, pending, event)
                true
            }
            // Nothing to act on while a database opens or closes.
            controller.busy != null || controller.session == null -> true
            else -> false
        }
    }

    fun global(event: KeyEvent): Boolean {
        val tab = event.key.toIntOrNull()
        when {
            event.key == "?" -> help = true
            event.key == "q" -> controller.quit()
            event.key == "o" -> controller.chooseDatabase()
            event.key == "Escape" -> prompter.notice = null
            event.key == "Tab" || event.key == "ArrowRight" -> selected = (selected + 1) % screens.size
            event.key == "ArrowLeft" -> selected = (selected - 1 + screens.size) % screens.size
            tab != null && tab in 1..screens.size -> selected = tab - 1
            else -> return false
        }
        return true
    }

    Box(
        modifier =
            Modifier
                .size(width, size.rows)
                .onPreviewKeyEvent(::preview)
                .onKeyEvent(::global),
    ) {
        Column {
            TabBar(screens, selected, width)
            Box(modifier = Modifier.size(width, bodyHeight)) {
                if (session != null && work != null) {
                    val scope =
                        TuiScreenScope(session, work, prompter, width, bodyHeight, browsedAccount) { account ->
                            browsedAccount = account
                            screens.indexOf(TransactionsScreen).takeIf { it >= 0 }?.let { selected = it }
                        }
                    screens[selected].content(scope)
                }
            }
            Footer(controller, vaultState, width)
        }
        val dialogWidth = (width - 4).coerceIn(1, MAX_DIALOG_WIDTH)
        val request = prompter.request
        when {
            request != null -> PromptDialog(request, prompter.notice, dialogWidth)
            prompter.info != null -> InfoDialog(checkNotNull(prompter.info), dialogWidth)
            help -> HelpOverlay(screens.getOrNull(selected), dialogWidth)
            busy != null -> Panel(dialogWidth) { Text(fit("$busy…", dialogWidth - PANEL_INSET)) }
            choice != null -> ChoiceDialog(choice, prompter.notice, dialogWidth)
        }
    }
}

private fun handleChoiceKey(
    controller: TuiController,
    choice: DatabaseChoice,
    event: KeyEvent,
) {
    when (event.key) {
        "Enter" ->
            choice.editor.text
                .trim()
                .takeIf { it.isNotEmpty() }
                ?.let { controller.open(DbLocation(expandTilde(it))) }
        "Escape" -> if (choice.cancellable) controller.cancelChoice() else controller.prompter.notice = null
        else -> choice.editor.handle(event)
    }
}

@Composable
private fun TabBar(
    screens: List<TuiScreen>,
    selected: Int,
    width: Int,
) {
    val labels =
        listOf(" Money Manager " to TextStyle.Bold) +
            screens.mapIndexed { index, screen ->
                " ${index + 1} ${screen.title} " to if (index == selected) TextStyle.Invert else TextStyle.Unspecified
            }
    // Cut where the terminal ends rather than wrapping or running off it.
    Row {
        var remaining = width
        for ((label, style) in labels) {
            if (remaining <= 0) break
            val shown = fit(label, remaining)
            Text(shown, textStyle = style)
            remaining -= shown.length
        }
    }
}

@Composable
private fun Footer(
    controller: TuiController,
    vaultState: VaultState,
    width: Int,
) {
    val notice = controller.prompter.notice
    if (notice != null) {
        Text(fit(notice.message, width), color = if (notice.error) Color.Red else Color.Yellow)
        return
    }
    val database = controller.session?.location?.toString() ?: "No database open"
    val vault =
        when (vaultState) {
            VaultState.Unbound, is VaultState.NoFile -> "no vault"
            is VaultState.Locked -> "vault locked"
            is VaultState.Unlocked -> "vault unlocked"
        }
    // The keys stay visible however long the path: it's the path that gets cut.
    val keys = " · ? help · q quit"
    val status = "$database · $vault"
    val line = if (width > keys.length * 2) fit(status, width - keys.length) + keys else fit(status + keys, width)
    Text(line, textStyle = TextStyle.Dim)
}

@Composable
private fun PromptDialog(
    request: PromptRequest,
    notice: Notice?,
    width: Int,
) {
    val inner = width - PANEL_INSET
    Panel(width) {
        NoticeLine(notice, inner)
        Text(fit(request.label, inner), textStyle = TextStyle.Bold)
        when (request) {
            is PromptRequest.Line -> {
                LineField(request.editor, inner, request.masked)
                Text(fit("Enter to confirm · Esc to cancel", inner), textStyle = TextStyle.Dim)
            }
            is PromptRequest.Confirm ->
                Text(fit(if (request.default) "[Y/n]" else "[y/N]", inner), textStyle = TextStyle.Dim)
        }
    }
}

@Composable
private fun InfoDialog(
    info: Info,
    width: Int,
) {
    val inner = width - PANEL_INSET
    Panel(width) {
        Text(fit(info.title, inner), textStyle = TextStyle.Bold)
        for (line in wrap(info.text, inner)) Text(line)
        Text(fit("Enter or Esc to close", inner), textStyle = TextStyle.Dim)
    }
}

/** [text] in lines of at most [width] columns, broken at spaces where it can be and mid-word (a URL) where it can't. */
internal fun wrap(
    text: String,
    width: Int,
): List<String> {
    if (width <= 0) return emptyList()
    return text.lines().flatMap { paragraph ->
        val lines = mutableListOf<String>()
        var line = ""
        for (word in paragraph.split(' ')) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (candidate.length <= width) {
                line = candidate
                continue
            }
            if (line.isNotEmpty()) lines += line
            line = word
            while (line.length > width) {
                lines += line.take(width)
                line = line.drop(width)
            }
        }
        lines + line
    }
}

@Composable
private fun ChoiceDialog(
    choice: DatabaseChoice,
    notice: Notice?,
    width: Int,
) {
    val inner = width - PANEL_INSET
    Panel(width) {
        NoticeLine(notice, inner)
        Text(fit("Open a database", inner), textStyle = TextStyle.Bold)
        LineField(choice.editor, inner)
        Text(fit("A new database is created when there's no file at this path.", inner), textStyle = TextStyle.Dim)
        // q is a letter of the path here, so only Ctrl-C quits.
        val escape = if (choice.cancellable) "Esc to go back" else "Ctrl-C to quit"
        Text(fit("Enter to open · $escape", inner), textStyle = TextStyle.Dim)
    }
}

@Composable
private fun HelpOverlay(
    screen: TuiScreen?,
    width: Int,
) {
    val inner = width - PANEL_INSET
    val keysWidth = (GLOBAL_BINDINGS + screen?.bindings.orEmpty()).maxOf { it.keys.length } + 2

    @Composable
    fun Bindings(bindings: List<KeyBinding>) {
        for (binding in bindings) {
            Text(fit(binding.keys.padEnd(keysWidth) + binding.description, inner))
        }
    }

    Panel(width) {
        Text(fit("Keys", inner), textStyle = TextStyle.Bold)
        Bindings(GLOBAL_BINDINGS)
        if (screen != null && screen.bindings.isNotEmpty()) {
            Text(fit(screen.title, inner), textStyle = TextStyle.Bold)
            Bindings(screen.bindings)
        }
        Text(fit("? or Esc to close", inner), textStyle = TextStyle.Dim)
    }
}

@Composable
private fun NoticeLine(
    notice: Notice?,
    width: Int,
) {
    if (notice != null) Text(fit(notice.message, width), color = if (notice.error) Color.Red else Color.Yellow)
}

/** A bordered box over the centre of the screen (or of the [areaColumns] × [areaRows] it's in), blanking what's beneath it. */
@Composable
internal fun Panel(
    columns: Int,
    areaColumns: Int = LocalTerminalState.current.size.columns,
    areaRows: Int = LocalTerminalState.current.size.rows,
    content: @Composable () -> Unit,
) {
    Box(modifier = Modifier.size(areaColumns, areaRows), contentAlignment = Alignment.Center) {
        Column(
            modifier =
                Modifier
                    .width(columns)
                    .drawBehind {
                        drawRect(' ')
                        if (width >= 2 && height >= 2) {
                            val bar = "─".repeat(width - 2)
                            drawText(0, 0, "┌$bar┐")
                            for (row in 1 until height - 1) {
                                drawText(row, 0, "│")
                                drawText(row, width - 1, "│")
                            }
                            drawText(height - 1, 0, "└$bar┘")
                        }
                    }.padding(horizontal = PANEL_INSET / 2, vertical = 1),
        ) {
            content()
        }
    }
}

/** Columns a panel's border and padding take from its width. */
internal const val PANEL_INSET = 4
internal const val MAX_DIALOG_WIDTH = 72

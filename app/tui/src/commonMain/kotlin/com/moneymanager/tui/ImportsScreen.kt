package com.moneymanager.tui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.layout.padding
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.moneymanager.presenter.imports.ImportKind
import com.moneymanager.presenter.imports.ImportsEvent
import com.moneymanager.presenter.imports.ImportsModel
import com.moneymanager.presenter.imports.presentImports
import com.moneymanager.terminal.TaskProgressView
import com.moneymanager.terminal.fit
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

private val FRAME_INTERVAL = 150.milliseconds

/**
 * Starts the same imports as `mm import dirs|apis|all` and shows them live. A run keeps going on another tab;
 * what it leaves for a person is listed here until the next run, to be resolved in the desktop app.
 */
val ImportsScreen =
    TuiScreen(
        title = "Imports",
        bindings =
            listOf(
                KeyBinding("d", "Import directories"),
                KeyBinding("a", "Import APIs"),
                KeyBinding("f", "Import APIs, downloading everything again (force full)"),
                KeyBinding("i", "Import all: directories and APIs at once"),
                KeyBinding("c", "Cancel the running import"),
            ),
    ) { scope ->
        val work = scope.work
        ImportsView(presentImports(work.tasks, work.imports, work.scope), scope.width, scope.height)
    }

@Composable
private fun ImportsView(
    model: ImportsModel,
    width: Int,
    height: Int,
) {
    val inner = (width - 2).coerceAtLeast(0)
    // Moves indeterminate bars and elapsed times while anything runs.
    var frame by remember { mutableIntStateOf(0) }
    val anyRunning = model.tasks.any { it.isRunning }
    LaunchedEffect(anyRunning) {
        while (anyRunning) {
            delay(FRAME_INTERVAL)
            frame++
        }
    }

    fun key(event: KeyEvent): Boolean {
        when (event.key) {
            "d" -> model.eventSink(ImportsEvent.Start(ImportKind.DIRECTORIES))
            "a" -> model.eventSink(ImportsEvent.Start(ImportKind.APIS))
            "f" -> model.eventSink(ImportsEvent.Start(ImportKind.APIS, forceFull = true))
            "i" -> model.eventSink(ImportsEvent.Start(ImportKind.ALL))
            "c" -> model.eventSink(ImportsEvent.Cancel)
            else -> return false
        }
        return true
    }

    Column(modifier = Modifier.padding(horizontal = 1).onKeyEvent(::key)) {
        Text(fit("Imports", inner), textStyle = TextStyle.Bold)
        val actions =
            if (model.running) {
                "Importing… · c cancel"
            } else {
                "d directories · a APIs · f APIs (force full) · i all"
            }
        Text(fit(actions, inner), textStyle = TextStyle.Dim)
        val error = model.error
        if (error != null) Text(fit(error, inner), color = Color.Red) else Text("")
        // Only the latest run: the ones before it are history.
        val latest = model.tasks.lastOrNull { it.parentId == null }
        if (latest == null) {
            Text(fit("Nothing imported yet in this session.", inner), textStyle = TextStyle.Dim)
        } else {
            TaskProgressView(model.tasks, inner, sinceId = latest.id, frame = frame)
        }
        Attention(model, inner, (height / 2).coerceAtLeast(1))
    }
}

@Composable
private fun Attention(
    model: ImportsModel,
    width: Int,
    rows: Int,
) {
    val run = model.lastRun ?: return
    val items = model.attention
    val errors = run.errors
    if (items.isEmpty() && errors.isEmpty()) return
    Text("")
    for (error in errors) Text(fit("✗ $error", width), color = Color.Red)
    if (items.isEmpty()) return
    Text(fit("Needs attention · ${items.size}", width), textStyle = TextStyle.Bold)
    val shown = if (items.size > rows) items.take((rows - 1).coerceAtLeast(0)) else items
    for (item in shown) {
        Text(fit("  ${item.subject} · ${item.reason} (${item.kind})", width), color = Color.Yellow)
    }
    if (shown.size < items.size) Text(fit("  +${items.size - shown.size} more", width), textStyle = TextStyle.Dim)
    Text(fit("Resolve these in the desktop app; the TUI can't yet.", width), textStyle = TextStyle.Dim)
}

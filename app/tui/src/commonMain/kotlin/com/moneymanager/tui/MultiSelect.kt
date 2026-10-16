package com.moneymanager.tui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.moneymanager.terminal.fit

/** What a key did to a [MultiSelect]. */
sealed interface PickResult<out T> {
    data object Continue : PickResult<Nothing>

    data object Cancel : PickResult<Nothing>

    data class Apply<T>(
        val selected: Set<T>,
    ) : PickResult<T>
}

/**
 * Ticking any number of [items] (a value and its label), narrowed by typing. An empty selection means "any", as
 * the accounts filters read it.
 */
class MultiSelect<T>(
    val title: String,
    private val items: List<Pair<T, String>>,
    initial: Set<T>,
) {
    val filter = LineEditor()
    var selected: Set<T> by mutableStateOf(initial)
        private set
    var cursor by mutableIntStateOf(0)
        private set

    /** The items whose label contains the filter text. */
    val shown: List<Pair<T, String>>
        get() = items.filter { (_, label) -> label.contains(filter.text, ignoreCase = true) }

    fun handle(
        event: KeyEvent,
        pageRows: Int,
    ): PickResult<T> {
        val last = (shown.size - 1).coerceAtLeast(0)
        val current = cursor.coerceIn(0, last)
        when {
            event.key == "Enter" -> return PickResult.Apply(selected)
            event.key == "Escape" -> return PickResult.Cancel
            event.ctrl && event.key == "a" -> selected = emptySet()
            event.key == " " -> shown.getOrNull(current)?.let { (value, _) -> selected = toggle(value) }
            event.key == "ArrowUp" -> cursor = (current - 1).coerceAtLeast(0)
            event.key == "ArrowDown" -> cursor = (current + 1).coerceAtMost(last)
            event.key == "PageUp" -> cursor = (current - pageRows).coerceAtLeast(0)
            event.key == "PageDown" -> cursor = (current + pageRows).coerceAtMost(last)
            filter.handle(event) -> cursor = 0
        }
        return PickResult.Continue
    }

    private fun toggle(value: T) = if (value in selected) selected - value else selected + value
}

/** [picker] in a dialog centred on the [areaColumns] × [areaRows] it's drawn over. */
@Composable
fun MultiSelectDialog(
    picker: MultiSelect<*>,
    areaColumns: Int,
    areaRows: Int,
) {
    val width = (areaColumns - 4).coerceIn(1, MAX_DIALOG_WIDTH)
    val inner = width - PANEL_INSET
    val rows = multiSelectRows(areaRows)
    val shown = picker.shown
    val current = picker.cursor.coerceIn(0, (shown.size - 1).coerceAtLeast(0))
    val first = (current - rows + 1).coerceAtLeast(0)
    Panel(width, areaColumns, areaRows) {
        val count = if (picker.selected.isEmpty()) "any" else "${picker.selected.size} picked"
        Text(fit("${picker.title} · $count", inner), textStyle = TextStyle.Bold)
        Row {
            Text("Filter: ", textStyle = TextStyle.Dim)
            LineField(picker.filter, (inner - "Filter: ".length).coerceAtLeast(1))
        }
        if (shown.isEmpty()) Text(fit("Nothing matches.", inner), textStyle = TextStyle.Dim)
        shown.drop(first).take(rows).forEachIndexed { offset, (value, label) ->
            val mark = if (value in picker.selected) "[x] " else "[ ] "
            Text(fit(mark + label, inner), textStyle = if (first + offset == current) TextStyle.Invert else TextStyle.Unspecified)
        }
        Text(fit("Space to tick · Ctrl-A for any · Enter to apply · Esc to cancel", inner), textStyle = TextStyle.Dim)
    }
}

/** The item rows a picker dialog fits in [areaRows]: what's left after its border, title, filter and hint lines. */
internal fun multiSelectRows(areaRows: Int) = (areaRows - PICKER_CHROME_ROWS).coerceAtLeast(1)

private const val PICKER_CHROME_ROWS = 6

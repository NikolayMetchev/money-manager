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

/** One line of editable text with a cursor: what every text prompt in the TUI types into. */
class LineEditor(
    initial: String = "",
) {
    var text by mutableStateOf(initial)
        private set
    var cursor by mutableIntStateOf(initial.length)
        private set

    /** Applies [event] when it's an editing key; false lets the caller handle it (Enter, Escape, …). */
    fun handle(event: KeyEvent): Boolean {
        val key = event.key
        when {
            event.ctrl && key == "u" -> replace("", 0)
            event.ctrl || event.alt -> return false
            key == "Backspace" -> if (cursor > 0) replace(text.removeRange(cursor - 1, cursor), cursor - 1)
            key == "Delete" -> if (cursor < text.length) replace(text.removeRange(cursor, cursor + 1), cursor)
            key == "ArrowLeft" -> cursor = (cursor - 1).coerceAtLeast(0)
            key == "ArrowRight" -> cursor = (cursor + 1).coerceAtMost(text.length)
            key == "Home" -> cursor = 0
            key == "End" -> cursor = text.length
            key.length == 1 -> replace(text.substring(0, cursor) + key + text.substring(cursor), cursor + 1)
            else -> return false
        }
        return true
    }

    private fun replace(
        next: String,
        nextCursor: Int,
    ) {
        text = next
        cursor = nextCursor
    }
}

/** [editor]'s text, masked with `•` when it's a secret, scrolled so the cursor (shown inverted) stays within [width]. */
@Composable
fun LineField(
    editor: LineEditor,
    width: Int,
    masked: Boolean = false,
) {
    val shown = if (masked) "•".repeat(editor.text.length) else editor.text
    val visible = (width - 1).coerceAtLeast(1)
    val start = (editor.cursor - visible + 1).coerceAtLeast(0)
    val before = shown.substring(start, editor.cursor)
    val atCursor = shown.getOrNull(editor.cursor)?.toString() ?: " "
    val after = shown.drop(editor.cursor + 1).take((visible - before.length - 1).coerceAtLeast(0))
    Row {
        Text(before)
        Text(atCursor, textStyle = TextStyle.Invert)
        Text(after)
    }
}

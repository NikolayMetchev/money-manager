package com.moneymanager.ui.components.csv

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moneymanager.compose.scrollbar.HorizontalScrollbarForScrollState
import com.moneymanager.compose.scrollbar.VerticalScrollbarForLazyList
import com.moneymanager.domain.model.csv.PdfTextLine
import com.moneymanager.ui.navigation.linuxHorizontalScrollWheel

private val NUMBER_COLUMN_WIDTH = 56.dp

/**
 * A PDF import's text lines as PDF line rules read them: page, line number and the text in monospace, so the
 * runs of spaces that keep columns apart line up as they do for a `Capture` pattern.
 */
@Composable
fun PdfLinesTable(
    lines: List<PdfTextLine>,
    modifier: Modifier = Modifier,
) {
    val lazyListState = rememberLazyListState()
    val horizontalScrollState = rememberScrollState()
    val monospace = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    Column(modifier = modifier.linuxHorizontalScrollWheel(horizontalScrollState)) {
        Row(modifier = Modifier.padding(vertical = 4.dp)) {
            NumberCell("Page", monospace.copy(fontWeight = FontWeight.Bold))
            NumberCell("Line", monospace.copy(fontWeight = FontWeight.Bold))
            Text("Text", style = monospace.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(start = 12.dp))
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            SelectionContainer {
                LazyColumn(state = lazyListState, modifier = Modifier.horizontalScroll(horizontalScrollState)) {
                    items(lines, key = { "${it.page}:${it.line}" }) { line ->
                        Row {
                            NumberCell(line.page.toString(), monospace)
                            NumberCell(line.line.toString(), monospace)
                            Text(line.text, style = monospace, softWrap = false, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
            VerticalScrollbarForLazyList(
                lazyListState = lazyListState,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
        HorizontalScrollbarForScrollState(
            scrollState = horizontalScrollState,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun NumberCell(
    text: String,
    style: TextStyle,
) {
    Text(
        text,
        style = style,
        textAlign = TextAlign.End,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(NUMBER_COLUMN_WIDTH),
    )
}

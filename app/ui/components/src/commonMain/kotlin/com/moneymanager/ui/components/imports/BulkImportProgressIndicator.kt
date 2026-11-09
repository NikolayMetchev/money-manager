package com.moneymanager.ui.components.imports

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moneymanager.humanreadable.HumanReadable
import com.moneymanager.tasks.Progress

/**
 * One determinate bar for a whole bulk import run, driven by the row-weighted fraction so it advances within large
 * files instead of jumping once per file, over five lines of one line each: files done, rows done, the its folder, the current file name
 * and what is being done to it. Every line is always there and long text is cut off rather than wrapped, so nothing
 * moves as the run goes on. Shared by the staged-file "Import all"/"Re-import all" dialogs.
 */
@Composable
fun BulkImportProgressIndicator(progress: Progress) {
    Column(modifier = Modifier.fillMaxWidth()) {
        LinearProgressIndicator(progress = { progress.fraction ?: 0f }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        bulkProgressLines(progress).forEachIndexed { line, text ->
            // A long folder path loses its front: the folders nearest the file say the most.
            ProgressLine(text, if (line == PATH_LINE) TextOverflow.StartEllipsis else TextOverflow.Ellipsis)
        }
    }
}

/**
 * The indicator's six lines: the phase, files, rows, its folder, current file name, current step (blank where unknown).
 */

fun bulkProgressLines(progress: Progress): List<String> {
    val item = progress.item
    val files = item?.let { "${it.done} of ${it.count} ${it.noun} (${percent(it.done, it.count)}%)" }.orEmpty()
    val total = progress.total
    val rows =
        if (total != null) {
            val done = progress.processed ?: 0
            "${HumanReadable.count(done.toLong())} of ${HumanReadable.count(total.toLong())} rows " +
                "(${percent(done, total)}%)"
        } else {
            ""
        }
    val path = item?.name.orEmpty()
    val separator = path.lastIndexOfAny(charArrayOf('/', '\\'))
    // Trimmed because a Drive path joins its parts with " / ", so the name would otherwise start with a space.
    val fileName = path.substring(separator + 1).trim()
    val folder = if (separator >= 0) path.substring(0, separator).trim() else ""
    val phase = progress.phase?.let { "Phase ${it.number} of ${it.count}: ${it.name}" }.orEmpty()
    return listOf(phase, files, rows, folder, fileName, item?.step ?: progress.detail)
}

@Composable
private fun ProgressLine(
    text: String,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    Text(
        // A blank line still takes its height, so the dialog never resizes.
        text = text.ifEmpty { " " },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = overflow,
    )
}

private fun percent(
    done: Int,
    count: Int,
): Int = if (count > 0) (done.toLong() * PERCENT / count).toInt() else 0

private const val PERCENT = 100

private const val PATH_LINE = 3

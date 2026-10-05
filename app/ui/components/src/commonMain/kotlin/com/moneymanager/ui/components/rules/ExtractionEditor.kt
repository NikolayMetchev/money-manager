package com.moneymanager.ui.components.rules

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.moneymanager.domain.model.rules.Extraction

/**
 * An optional [Extraction]: a checkbox labelled [label] turns it on (as the identity `(.*)` → `$1`), then
 * its pattern and output template are editable. Null means no extraction.
 */
@Composable
fun ExtractionEditor(
    label: String,
    extraction: Extraction?,
    onChange: (Extraction?) -> Unit,
    enabled: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = extraction != null,
            onCheckedChange = { on -> onChange(if (on) Extraction(pattern = "(.*)", outputTemplate = "$1") else null) },
            enabled = enabled,
        )
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
    extraction?.let { e ->
        OutlinedTextField(
            value = e.pattern,
            onValueChange = { onChange(e.copy(pattern = it)) },
            label = { Text("Pattern") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = enabled,
        )
        OutlinedTextField(
            value = e.outputTemplate,
            onValueChange = { onChange(e.copy(outputTemplate = it)) },
            label = { Text($$"Result ($0 whole match, $1… groups)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = enabled,
        )
    }
}

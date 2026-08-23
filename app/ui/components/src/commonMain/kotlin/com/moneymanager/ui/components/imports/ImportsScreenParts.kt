package com.moneymanager.ui.components.imports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The title row of an import list screen: the screen title, the Strategies button and the format's
 * own file-picker button, plus any [extraActions] a format adds (e.g. CSV's Excel picker).
 */
@Composable
fun ImportsScreenHeader(
    title: String,
    importButtonLabel: String,
    isImporting: Boolean,
    onImportClick: () -> Unit,
    onStrategiesClick: () -> Unit,
    extraActions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onStrategiesClick) {
                Text("Strategies")
            }
            TextButton(
                onClick = onImportClick,
                enabled = !isImporting,
            ) {
                if (isImporting) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp))
                } else {
                    Text(importButtonLabel)
                }
            }
            extraActions()
        }
    }
}

/** The result banner shown under an import screen's header after a file-picker run. */
@Composable
fun ImportStatusMessage(
    message: String?,
    isError: Boolean,
) {
    message?.let {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = it,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** The Unimported / Imported / Ignored tabs every import list screen shows. */
@Composable
fun ImportTabsRow(
    selectedTab: Int,
    unimportedCount: Int,
    importedCount: Int,
    ignoredCount: Int,
    onTabSelected: (Int) -> Unit,
) {
    SecondaryTabRow(selectedTabIndex = selectedTab) {
        Tab(
            selected = selectedTab == ImportTab.UNIMPORTED,
            onClick = { onTabSelected(ImportTab.UNIMPORTED) },
            text = { Text("Unimported ($unimportedCount)") },
        )
        Tab(
            selected = selectedTab == ImportTab.IMPORTED,
            onClick = { onTabSelected(ImportTab.IMPORTED) },
            text = { Text("Imported ($importedCount)") },
        )
        Tab(
            selected = selectedTab == ImportTab.IGNORED,
            onClick = { onTabSelected(ImportTab.IGNORED) },
            text = { Text("Ignored ($ignoredCount)") },
        )
    }
}

/** A centred placeholder filling the rest of an import screen when there is nothing to list. */
@Composable
fun ImportsEmptyMessage(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The placeholder text for an empty [selectedTab]; see [ImportTab]. */
fun emptyImportTabMessage(selectedTab: Int): String =
    when (selectedTab) {
        ImportTab.UNIMPORTED -> "All files have been imported."
        ImportTab.IMPORTED -> "No files imported yet."
        else -> "No ignored files."
    }

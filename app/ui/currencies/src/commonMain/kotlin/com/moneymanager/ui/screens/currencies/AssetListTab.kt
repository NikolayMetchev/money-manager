package com.moneymanager.ui.screens.currencies

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.compose.scrollbar.VerticalScrollbarForLazyList

/**
 * The list screen shared by the fiat and crypto tabs: a headline, the items (or an empty-state
 * message) keyed by [itemKey] so per-row state follows its asset, a search over [searchTexts] (the crypto list runs to
 * thousands of rows), and a "+" button that opens [createDialog], which is handed the callback that closes it.
 */
@Composable
internal fun <T> AssetListTab(
    title: String,
    items: List<T>,
    itemKey: (T) -> Long,
    searchTexts: (T) -> List<String>,
    emptyMessage: String,
    createDialog: @Composable (onClose: () -> Unit) -> Unit,
    itemContent: @Composable (T) -> Unit,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    val shown =
        // searchTexts is a key because it reads the aliases, which change without the items changing.
        remember(items, search, searchTexts) {
            val query = search.trim()
            if (query.isEmpty()) items else items.filter { item -> searchTexts(item).any { it.contains(query, ignoreCase = true) } }
        }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            if (items.isNotEmpty()) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Search by code, name or alias") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
            }

            if (items.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = emptyMessage,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val lazyListState = rememberLazyListState()
                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        state = lazyListState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(shown, key = itemKey) { item -> itemContent(item) }
                    }
                    VerticalScrollbarForLazyList(
                        lazyListState = lazyListState,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = { showCreateDialog = true },
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
        ) {
            Text("+", style = MaterialTheme.typography.headlineLarge)
        }

        if (showCreateDialog) {
            createDialog { showCreateDialog = false }
        }
    }
}

/**
 * One asset row: code, name and any [details] below them, any [extraActions], then a delete button that opens
 * [deleteDialog].
 */
@Composable
internal fun AssetCard(
    code: String,
    name: String,
    deleteDialog: @Composable (onDismiss: () -> Unit) -> Unit,
    details: @Composable () -> Unit = {},
    extraActions: @Composable RowScope.() -> Unit = {},
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = code, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                details()
            }
            extraActions()
            IconButton(onClick = { showDeleteDialog = true }) {
                Text(text = "🗑️", style = MaterialTheme.typography.titleMedium)
            }
        }
    }

    if (showDeleteDialog) {
        deleteDialog { showDeleteDialog = false }
    }
}

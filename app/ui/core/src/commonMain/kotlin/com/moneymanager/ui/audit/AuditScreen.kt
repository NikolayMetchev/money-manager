package com.moneymanager.ui.audit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.compose.scrollbar.VerticalScrollbarForLazyList
import org.lighthousegames.logging.logging

private val logger = logging()

data class AuditScreenData<D>(val title: String, val diffs: List<D>)

@Composable
fun <D : Any> AuditScreen(
    defaultTitle: String,
    entityTypeName: String,
    loadKey: Any,
    loadData: suspend () -> AuditScreenData<D>,
    diffKey: (D) -> Long,
    onBack: () -> Unit,
    diffCard: @Composable (D) -> Unit,
) {
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var screenData by remember { mutableStateOf<AuditScreenData<D>?>(null) }

    LaunchedEffect(loadKey) {
        isLoading = true
        errorMessage = null
        try {
            screenData = loadData()
        } catch (expected: Exception) {
            logger.error(expected) { "Failed to load audit history: ${expected.message}" }
            errorMessage = "Failed to load audit history: ${expected.message}"
        } finally {
            isLoading = false
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Text(
                    text = "\u2190",
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = screenData?.title ?: defaultTitle,
                style = MaterialTheme.typography.headlineSmall,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (errorMessage != null) {
            Text(
                text = errorMessage!!,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            val diffs = screenData?.diffs.orEmpty()
            if (diffs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No audit history found for this $entityTypeName.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val auditListState = rememberLazyListState()
                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        state = auditListState,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(diffs, key = { diffKey(it) }) { diff ->
                            diffCard(diff)
                        }
                    }
                    VerticalScrollbarForLazyList(
                        lazyListState = auditListState,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

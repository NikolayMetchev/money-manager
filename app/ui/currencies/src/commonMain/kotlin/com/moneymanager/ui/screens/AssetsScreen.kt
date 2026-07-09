package com.moneymanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.compose.scrollbar.VerticalScrollbarForLazyList
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.importengineapi.deleteCrypto
import com.moneymanager.ui.LocalImportEngine
import com.moneymanager.ui.components.CreateCryptoDialog
import com.moneymanager.ui.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

private val assetsLogger = logging()

/**
 * The Assets screen: two tabs, Currencies (fiat) and Crypto, each listing the relevant assets and
 * offering manual creation. Fiat and crypto are sibling asset subtypes, so they share this screen.
 */
@Composable
fun AssetsScreen(
    currencyRepository: CurrencyReadRepository,
    cryptoRepository: CryptoReadRepository,
    onCurrencyAuditClick: (Currency) -> Unit = {},
) {
    var selectedTab by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Currencies") },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Crypto") },
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> CurrenciesScreen(currencyRepository = currencyRepository, onAuditClick = onCurrencyAuditClick)
                else -> CryptoAssetsTab(cryptoRepository = cryptoRepository)
            }
        }
    }
}

@Composable
private fun CryptoAssetsTab(cryptoRepository: CryptoReadRepository) {
    val cryptoAssets by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        cryptoRepository.getAllCryptoAssets()
    }
    var showCreateDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
        ) {
            Text(
                text = "Your Crypto Assets",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            if (cryptoAssets.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No crypto assets yet. Add your first one!",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val lazyListState = rememberLazyListState()
                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(state = lazyListState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(cryptoAssets) { crypto -> CryptoAssetCard(crypto = crypto) }
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
            CreateCryptoDialog(
                onCryptoCreated = { showCreateDialog = false },
                onDismiss = { showCreateDialog = false },
            )
        }
    }
}

@Composable
private fun CryptoAssetCard(crypto: CryptoAsset) {
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
                Text(text = crypto.code, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = crypto.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { showDeleteDialog = true }) {
                Text(text = "🗑️", style = MaterialTheme.typography.titleMedium)
            }
        }
    }

    if (showDeleteDialog) {
        DeleteCryptoDialog(crypto = crypto, onDismiss = { showDeleteDialog = false })
    }
}

@Composable
private fun DeleteCryptoDialog(
    crypto: CryptoAsset,
    onDismiss: () -> Unit,
) {
    val importEngine = LocalImportEngine.current
    var isDeleting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberSchemaAwareCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!isDeleting) onDismiss() },
        icon = { Text(text = "⚠️", style = MaterialTheme.typography.headlineMedium) },
        title = { Text("Delete Crypto Asset?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Are you sure you want to delete \"${crypto.code} - ${crypto.name}\"?",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "This action cannot be undone. All accounts holding this asset will be affected.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                errorMessage?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    isDeleting = true
                    errorMessage = null
                    scope.launch {
                        try {
                            importEngine.deleteCrypto(crypto.id)
                            onDismiss()
                        } catch (expected: Exception) {
                            assetsLogger.error(expected) { "Failed to delete crypto asset: ${expected.message}" }
                            errorMessage = "Failed to delete crypto asset: ${expected.message}"
                            isDeleting = false
                        }
                    }
                },
                enabled = !isDeleting,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                if (isDeleting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Delete")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isDeleting) { Text("Cancel") }
        },
    )
}

package com.moneymanager.ui.screens.currencies

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.importengineapi.deleteCrypto
import com.moneymanager.ui.components.CreateCryptoDialog
import com.moneymanager.ui.components.DestructiveConfirmDialog
import com.moneymanager.ui.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.ui.foundation.LocalImportEngine

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

    AssetListTab(
        title = "Your Crypto Assets",
        items = cryptoAssets,
        itemKey = { it.id.id },
        emptyMessage = "No crypto assets yet. Add your first one!",
        createDialog = { onClose -> CreateCryptoDialog(onCryptoCreated = { onClose() }, onDismiss = onClose) },
    ) { crypto ->
        AssetCard(
            code = crypto.code,
            name = crypto.name,
            deleteDialog = { onDismiss -> DeleteCryptoDialog(crypto = crypto, onDismiss = onDismiss) },
        )
    }
}

@Composable
private fun DeleteCryptoDialog(
    crypto: CryptoAsset,
    onDismiss: () -> Unit,
) {
    val importEngine = LocalImportEngine.current

    DestructiveConfirmDialog(
        title = "Delete Crypto Asset?",
        targetName = "${crypto.code} - ${crypto.name}",
        consequence = "This action cannot be undone. All accounts holding this asset will be affected.",
        failureMessage = "Failed to delete crypto asset",
        onConfirm = {
            importEngine.deleteCrypto(crypto.id)
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

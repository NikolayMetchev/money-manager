package com.moneymanager.ui.screens.currencies

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.moneymanager.compose.filepicker.rememberFilePicker
import com.moneymanager.compose.filepicker.rememberFileSaver
import com.moneymanager.database.json.AssetListExportCodec
import com.moneymanager.database.service.AssetExportService
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.importengineapi.deleteCrypto
import com.moneymanager.presenter.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.ui.components.CreateCryptoDialog
import com.moneymanager.ui.components.DestructiveConfirmDialog
import com.moneymanager.ui.foundation.LocalImportEngine
import kotlinx.coroutines.launch

/**
 * The Assets screen: two tabs, Currencies (fiat) and Crypto, each listing the relevant assets and
 * offering manual creation, with the aliases of each asset and an export and import of them all. Fiat and crypto are
 * sibling asset subtypes, so they share this screen.
 */
@Composable
fun AssetsScreen(
    currencyRepository: CurrencyReadRepository,
    cryptoRepository: CryptoReadRepository,
    assetExportService: AssetExportService,
    appVersion: AppVersion,
    onCurrencyAuditClick: (Currency) -> Unit = {},
) {
    var selectedTab by remember { mutableStateOf(0) }
    val currencies by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        currencyRepository.getAllCurrencies()
    }
    val cryptoAssets by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        cryptoRepository.getAllCryptoAssets()
    }
    val aliases by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        cryptoRepository.getAssetAliases()
    }
    val directory =
        remember(currencies, cryptoAssets, aliases) {
            AssetAliasDirectory(
                aliases,
                (currencies.map { it.code } + cryptoAssets.map { it.code }).mapTo(mutableSetOf()) { it.uppercase() },
            )
        }

    Column(modifier = Modifier.fillMaxSize()) {
        AssetExportActions(assetExportService, appVersion)
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
                0 -> {
                    CurrenciesScreen(
                        currencyRepository = currencyRepository,
                        onAuditClick = onCurrencyAuditClick,
                        assetDetails = { code -> AssetAliasRow(code, directory) },
                        aliasesOf = directory::allAliases,
                    )
                }

                else -> {
                    CryptoAssetsTab(cryptoAssets, directory)
                }
            }
        }
    }
}

// Export and import of every asset the published lists can't bring back, as for the global account mappings.
@Composable
private fun AssetExportActions(
    assetExportService: AssetExportService,
    appVersion: AppVersion,
) {
    val scope = rememberCoroutineScope()
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val fileSaver = rememberFileSaver(onResult = {})
    val filePicker =
        rememberFilePicker(
            mimeTypes = listOf("application/json"),
            onResult = { result ->
                if (result != null) {
                    scope.launch {
                        runCatching { assetExportService.importAssets(AssetListExportCodec.decode(result.content)) }
                            .onSuccess { errorMessage = null }
                            .onFailure { errorMessage = "Failed to import: ${it.message}" }
                    }
                }
            },
        )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
        IconButton(
            onClick = {
                scope.launch {
                    runCatching { fileSaver.launch("assets.json", AssetListExportCodec.encode(assetExportService.toExport(appVersion))) }
                        .onSuccess { errorMessage = null }
                        .onFailure { errorMessage = "Failed to export: ${it.message}" }
                }
            },
        ) {
            Icon(imageVector = Icons.Filled.Share, contentDescription = "Export assets")
        }
        IconButton(onClick = { filePicker.launch() }) {
            Text(text = "⭳", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun CryptoAssetsTab(
    cryptoAssets: List<CryptoAsset>,
    directory: AssetAliasDirectory,
) {
    AssetListTab(
        title = "Your Crypto Assets",
        items = cryptoAssets,
        itemKey = { it.id.id },
        searchTexts = { listOf(it.code, it.name) + directory.allAliases(it.code) },
        emptyMessage = "No crypto assets yet. Add your first one!",
        createDialog = { onClose -> CreateCryptoDialog(onCryptoCreated = { onClose() }, onDismiss = onClose) },
    ) { crypto ->
        AssetCard(
            code = crypto.code,
            name = crypto.name,
            deleteDialog = { onDismiss -> DeleteCryptoDialog(crypto = crypto, onDismiss = onDismiss) },
            details = { AssetAliasRow(crypto.code, directory) },
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

package com.moneymanager.ui.screens.currencies

import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.importengineapi.deleteCurrency
import com.moneymanager.ui.components.CreateCurrencyDialog
import com.moneymanager.ui.components.DestructiveConfirmDialog
import com.moneymanager.ui.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.ui.foundation.LocalImportEngine

@Composable
fun CurrenciesScreen(
    currencyRepository: CurrencyReadRepository,
    onAuditClick: (Currency) -> Unit = {},
) {
    val currencies by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        currencyRepository.getAllCurrencies()
    }

    AssetListTab(
        title = "Your Currencies",
        items = currencies,
        itemKey = { it.id.id },
        emptyMessage = "No currencies yet. Add your first currency!",
        createDialog = { onClose -> CreateCurrencyDialog(onCurrencyCreated = { onClose() }, onDismiss = onClose) },
    ) { currency ->
        AssetCard(
            code = currency.code,
            name = currency.name,
            deleteDialog = { onDismiss -> DeleteCurrencyDialog(currency = currency, onDismiss = onDismiss) },
        ) {
            IconButton(onClick = { onAuditClick(currency) }) {
                Text(text = "📋", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun DeleteCurrencyDialog(
    currency: Currency,
    onDismiss: () -> Unit,
) {
    val importEngine = LocalImportEngine.current

    DestructiveConfirmDialog(
        title = "Delete Currency?",
        targetName = "${currency.code} - ${currency.name}",
        consequence = "This action cannot be undone. All accounts using this currency will be affected.",
        failureMessage = "Failed to delete currency",
        onConfirm = {
            importEngine.deleteCurrency(currency.id)
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

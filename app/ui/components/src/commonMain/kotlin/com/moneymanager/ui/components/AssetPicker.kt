@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.model.AssetId
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.util.onEnterKeyDown

/**
 * A searchable picker over every [Asset] — fiat currencies and crypto assets combined — used where a
 * leg can be denominated in either (e.g. a trade). Mirrors [CurrencyPicker] but returns the full
 * [Asset] so callers can build a [com.moneymanager.domain.model.Money] directly.
 */
@Composable
fun AssetPicker(
    selectedAssetId: AssetId?,
    onAssetSelected: (Asset) -> Unit,
    label: String,
    currencyRepository: CurrencyReadRepository,
    cryptoRepository: CryptoReadRepository,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = "Type to search...",
    isError: Boolean = false,
    focusRequester: FocusRequester? = null,
    onSubmit: (() -> Unit)? = null,
) {
    val currencies by currencyRepository
        .getAllCurrencies()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val cryptos by cryptoRepository
        .getAllCryptoAssets()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())

    val assets: List<Asset> = remember(currencies, cryptos) { currencies + cryptos }

    var expanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val filtered =
        remember(assets, searchQuery) {
            if (searchQuery.isBlank()) {
                assets
            } else {
                assets.filter { it.code.contains(searchQuery, ignoreCase = true) || it.name.contains(searchQuery, ignoreCase = true) }
            }
        }

    val selected = assets.find { it.id == selectedAssetId }

    fun label(asset: Asset): String {
        val kind = if (asset is CryptoAsset) " (crypto)" else ""
        return "${asset.code} - ${asset.name}$kind"
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = if (expanded) searchQuery else selected?.let { label(it) }.orEmpty(),
            onValueChange = { searchQuery = it },
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                    .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                    .let { if (onSubmit != null) it.onEnterKeyDown(onSubmit) else it },
            enabled = enabled,
            singleLine = true,
            isError = isError,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                searchQuery = ""
            },
        ) {
            filtered.forEach { asset ->
                DropdownMenuItem(
                    text = { Text(label(asset)) },
                    onClick = {
                        onAssetSelected(asset)
                        expanded = false
                        searchQuery = ""
                    },
                )
            }
        }
    }
}

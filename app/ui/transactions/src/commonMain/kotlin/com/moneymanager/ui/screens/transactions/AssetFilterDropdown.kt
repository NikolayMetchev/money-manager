@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.screens.transactions

import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.Asset

/** Narrows the list to one of the [assets] the account holds, or back to all of them (null). */
@Composable
internal fun AssetFilterDropdown(
    assets: List<Asset>,
    selectedAssetId: Long?,
    onSelected: (Asset?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = assets.find { it.id.id == selectedAssetId }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier.widthIn(max = 220.dp)) {
        OutlinedTextField(
            value = selected?.code ?: "All assets",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Asset") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("All assets") },
                onClick = {
                    onSelected(null)
                    expanded = false
                },
            )
            assets.forEach { asset ->
                DropdownMenuItem(
                    text = { Text("${asset.code} - ${asset.name}") },
                    onClick = {
                        onSelected(asset)
                        expanded = false
                    },
                )
            }
        }
    }
}

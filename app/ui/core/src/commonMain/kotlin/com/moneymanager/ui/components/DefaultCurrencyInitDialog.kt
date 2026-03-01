package com.moneymanager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.currency.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.SettingsRepository
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

@Composable
fun DefaultCurrencyInitDialog(
    currencyRepository: CurrencyRepository,
    settingsRepository: SettingsRepository,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    var selectedCurrencyId by remember { mutableStateOf<CurrencyId?>(null) }
    var initialized by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!initialized) {
            val localeCode = Currency.getDefaultCurrencyCode()
            if (localeCode != null) {
                val currency = currencyRepository.getCurrencyByCode(localeCode).firstOrNull()
                if (currency != null) {
                    selectedCurrencyId = currency.id
                }
            }
            initialized = true
        }
    }

    AlertDialog(
        onDismissRequest = { },
        title = { Text("Select Default Currency") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Choose the default currency for new transactions.")
                CurrencyPicker(
                    selectedCurrencyId = selectedCurrencyId,
                    onCurrencySelected = { selectedCurrencyId = it },
                    label = "Default Currency",
                    currencyRepository = currencyRepository,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    selectedCurrencyId?.let { id ->
                        scope.launch {
                            settingsRepository.setDefaultCurrencyId(id)
                        }
                    }
                },
                enabled = selectedCurrencyId != null,
            ) {
                Text("Confirm")
            }
        },
    )
}

@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.importengineapi.createTrade
import com.moneymanager.ui.LocalImportEngine
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.components.AssetPicker
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging
import kotlin.time.Clock

private val tradeEntryLogger = logging()

/**
 * Dialog for manually entering a cross-asset [com.moneymanager.domain.model.Trade] — e.g. buying
 * crypto: an amount of one asset leaves the "from" account and an amount of another enters the "to"
 * account. Denomination uses the full [AssetPicker] (fiat + crypto). Saves via
 * [com.moneymanager.importengineapi.ImportEngine.createTrade], which is re-import-idempotent.
 */
@Composable
fun TradeEntryDialog(
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    personRepository: PersonReadRepository,
    currencyRepository: CurrencyReadRepository,
    cryptoRepository: CryptoReadRepository,
    maintenance: Maintenance,
    onDismiss: () -> Unit,
    onSaved: () -> Unit = {},
) {
    var fromAccountId by remember { mutableStateOf<AccountId?>(null) }
    var toAccountId by remember { mutableStateOf<AccountId?>(null) }
    var fromAsset by remember { mutableStateOf<Asset?>(null) }
    var toAsset by remember { mutableStateOf<Asset?>(null) }
    var fromAmount by remember { mutableStateOf("") }
    var toAmount by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    val scope = rememberSchemaAwareCoroutineScope()
    val importEngine = LocalImportEngine.current

    fun parse(value: String): BigDecimal? = value.trim().takeIf { it.isNotBlank() }?.let { runCatching { BigDecimal(it) }.getOrNull() }

    val submit: () -> Unit = submit@{
        if (isSaving) return@submit
        val from = fromAccountId
        val to = toAccountId
        val fromA = fromAsset
        val toA = toAsset
        val fromValue = parse(fromAmount)
        val toValue = parse(toAmount)
        when {
            from == null || to == null -> errorMessage = "Select both accounts"
            from == to -> errorMessage = "A trade must move between two different accounts"
            fromA == null || toA == null -> errorMessage = "Select both assets"
            fromA.id == toA.id -> errorMessage = "A trade must exchange two different assets"
            fromValue == null || toValue == null -> errorMessage = "Enter both amounts"
            fromValue <= BigDecimal.ZERO || toValue <= BigDecimal.ZERO ->
                errorMessage = "Amounts must be greater than zero"
            else -> {
                errorMessage = null
                isSaving = true
                scope.launch {
                    try {
                        importEngine.createTrade(
                            timestamp = Clock.System.now(),
                            description = description.trim(),
                            fromAccountId = from,
                            fromAmount = Money.fromDisplayValue(fromValue, fromA),
                            toAccountId = to,
                            toAmount = Money.fromDisplayValue(toValue, toA),
                        )
                        maintenance.refreshMaterializedViews()
                        onSaved()
                        onDismiss()
                    } catch (expected: Exception) {
                        tradeEntryLogger.error(expected) { "Failed to create trade: ${expected.message}" }
                        errorMessage = expected.message ?: "Failed to create trade"
                        isSaving = false
                    }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("New Trade") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AccountPicker(
                    selectedAccountId = fromAccountId,
                    onAccountSelected = { fromAccountId = it },
                    label = "From Account",
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    personRepository = personRepository,
                    enabled = !isSaving,
                    excludeAccountId = toAccountId,
                )
                AssetPicker(
                    selectedAssetId = fromAsset?.id,
                    onAssetSelected = { fromAsset = it },
                    label = "You pay (asset)",
                    currencyRepository = currencyRepository,
                    cryptoRepository = cryptoRepository,
                    enabled = !isSaving,
                )
                OutlinedTextField(
                    value = fromAmount,
                    onValueChange = { fromAmount = it },
                    label = { Text("You pay (amount)") },
                    singleLine = true,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth(),
                )
                AccountPicker(
                    selectedAccountId = toAccountId,
                    onAccountSelected = { toAccountId = it },
                    label = "To Account",
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    personRepository = personRepository,
                    enabled = !isSaving,
                    excludeAccountId = fromAccountId,
                )
                AssetPicker(
                    selectedAssetId = toAsset?.id,
                    onAssetSelected = { toAsset = it },
                    label = "You receive (asset)",
                    currencyRepository = currencyRepository,
                    cryptoRepository = cryptoRepository,
                    enabled = !isSaving,
                )
                OutlinedTextField(
                    value = toAmount,
                    onValueChange = { toAmount = it },
                    label = { Text("You receive (amount)") },
                    singleLine = true,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    singleLine = true,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth(),
                )
                errorMessage?.let { Text(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = !isSaving) { Text("Create trade") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) { Text("Cancel") }
        },
    )
}

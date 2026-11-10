package com.moneymanager.ui.screens.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import co.touchlab.kermit.Logger
import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.importengineapi.createTrade
import com.moneymanager.importengineapi.setTradeExcluded
import com.moneymanager.importengineapi.updateTrade
import com.moneymanager.presenter.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.components.AssetPicker
import com.moneymanager.ui.components.DateTimePickerField
import com.moneymanager.ui.foundation.LocalImportEngine
import com.moneymanager.ui.util.formatAmount
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

private val tradeEntryLogger = Logger.withTag("TradeEntryDialog")

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
    trade: Trade? = null,
    feeTransfers: List<Transfer> = emptyList(),
    onDismiss: () -> Unit,
    onSaved: () -> Unit = {},
) {
    val timeZone = TimeZone.currentSystemDefault()
    var fromAccountId by remember(trade?.id) { mutableStateOf(trade?.fromAccountId) }
    var toAccountId by remember(trade?.id) { mutableStateOf(trade?.toAccountId) }
    var fromAsset by remember(trade?.id) { mutableStateOf(trade?.from?.asset) }
    var toAsset by remember(trade?.id) { mutableStateOf(trade?.to?.asset) }
    var fromAmount by remember(trade?.id) {
        mutableStateOf(
            trade
                ?.from
                ?.toDisplayValue()
                ?.toString()
                .orEmpty(),
        )
    }
    var toAmount by remember(trade?.id) {
        mutableStateOf(
            trade
                ?.to
                ?.toDisplayValue()
                ?.toString()
                .orEmpty(),
        )
    }
    var description by remember(trade?.id) { mutableStateOf(trade?.description.orEmpty()) }
    var dateTime by remember(trade?.id) {
        mutableStateOf((trade?.timestamp ?: Clock.System.now()).toLocalDateTime(timeZone))
    }
    val originalExclusion =
        trade?.attributes?.firstOrNull { it.attributeType.id.id == WellKnownIds.EXCLUDED_ATTR_TYPE_ID }
    var isExcluded by remember(trade?.id) { mutableStateOf(originalExclusion != null) }
    var exclusionReason by remember(trade?.id) { mutableStateOf(originalExclusion?.value.orEmpty()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    val scope = rememberSchemaAwareCoroutineScope()
    val importEngine = LocalImportEngine.current

    fun parse(value: String): BigDecimal? = value.trim().takeIf { it.isNotBlank() }?.let { runCatching { BigDecimal(it) }.getOrNull() }

    val hasChanges =
        trade == null ||
            fromAccountId != trade.fromAccountId ||
            toAccountId != trade.toAccountId ||
            fromAsset?.id != trade.from.asset.id ||
            toAsset?.id != trade.to.asset.id ||
            parse(fromAmount) != trade.from.toDisplayValue() ||
            parse(toAmount) != trade.to.toDisplayValue() ||
            description.trim() != trade.description ||
            dateTime.toInstant(timeZone) != trade.timestamp ||
            isExcluded != (originalExclusion != null) ||
            (isExcluded && exclusionReason.trim() != originalExclusion?.value)

    val submit: () -> Unit = submit@{
        if (isSaving || !hasChanges) return@submit
        val from = fromAccountId
        val to = toAccountId
        val fromA = fromAsset
        val toA = toAsset
        val fromValue = parse(fromAmount)
        val toValue = parse(toAmount)
        when {
            from == null || to == null -> {
                errorMessage = "Select both accounts"
            }

            fromA == null || toA == null -> {
                errorMessage = "Select both assets"
            }

            fromA.id == toA.id -> {
                errorMessage = "A trade must exchange two different assets"
            }

            fromValue == null || toValue == null -> {
                errorMessage = "Enter both amounts"
            }

            fromValue <= BigDecimal.ZERO || toValue <= BigDecimal.ZERO -> {
                errorMessage = "Amounts must be greater than zero"
            }

            else -> {
                errorMessage = null
                isSaving = true
                scope.launch {
                    try {
                        if (trade == null) {
                            importEngine.createTrade(
                                timestamp = dateTime.toInstant(timeZone),
                                description = description.trim(),
                                fromAccountId = from,
                                fromAmount = Money.fromDisplayValue(fromValue, fromA),
                                toAccountId = to,
                                toAmount = Money.fromDisplayValue(toValue, toA),
                            )
                        } else {
                            importEngine.updateTrade(
                                trade.copy(
                                    timestamp = dateTime.toInstant(timeZone),
                                    description = description.trim(),
                                    fromAccountId = from,
                                    from = Money.fromDisplayValue(fromValue, fromA),
                                    toAccountId = to,
                                    to = Money.fromDisplayValue(toValue, toA),
                                ),
                            )
                            if (isExcluded != (originalExclusion != null) ||
                                (isExcluded && exclusionReason.trim() != originalExclusion?.value)
                            ) {
                                importEngine.setTradeExcluded(
                                    trade.id,
                                    if (isExcluded) exclusionReason.trim() else null,
                                )
                            }
                        }
                        maintenance.refreshMaterializedViews()
                        onSaved()
                        onDismiss()
                    } catch (expected: Exception) {
                        tradeEntryLogger.e(expected) {
                            "Failed to ${if (trade == null) "create" else "update"} trade: ${expected.message}"
                        }
                        errorMessage = expected.message ?: "Failed to save trade"
                        isSaving = false
                    }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(if (trade == null) "New Trade" else "Edit Trade") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DateTimePickerField(
                    value = dateTime,
                    onValueChange = { dateTime = it },
                    enabled = !isSaving,
                )
                AccountPicker(
                    selectedAccountId = fromAccountId,
                    onAccountSelected = { fromAccountId = it },
                    label = "From Account",
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    personRepository = personRepository,
                    enabled = !isSaving,
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
                if (trade != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = isExcluded,
                            onCheckedChange = { isExcluded = it },
                            enabled = !isSaving,
                        )
                        Text("Excluded from balances")
                    }
                    if (isExcluded) {
                        OutlinedTextField(
                            value = exclusionReason,
                            onValueChange = { exclusionReason = it },
                            label = { Text("Exclusion reason") },
                            singleLine = true,
                            enabled = !isSaving,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (feeTransfers.isNotEmpty()) {
                        Text("Associated fees", style = MaterialTheme.typography.titleSmall)
                        feeTransfers.forEach { fee ->
                            Text(
                                "${formatAmount(fee.amount)} · ${fee.description.ifBlank { "Fee" }}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                errorMessage?.let { Text(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = !isSaving && hasChanges) {
                Text(if (trade == null) "Create trade" else "Save trade")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) { Text("Cancel") }
        },
    )
}

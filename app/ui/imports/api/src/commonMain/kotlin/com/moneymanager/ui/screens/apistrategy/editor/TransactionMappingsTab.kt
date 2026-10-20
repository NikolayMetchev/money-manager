package com.moneymanager.ui.screens.apistrategy.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.apistrategy.ApiAmountFormat
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.ui.screens.apistrategy.JsonPathEntry

/** Edits the top-level [ApiTransactionMappings] in place inside the strategy config. */
private fun ApiStrategyEditorState.updateTransactionMappings(block: ApiTransactionMappings.() -> ApiTransactionMappings) =
    updateConfig { mapBankTransactionMappings(block) }

@Composable
internal fun TransactionMappingsTab(
    state: ApiStrategyEditorState,
    txJsonPaths: List<JsonPathEntry>,
    txSampleLoaded: Boolean,
    onRequestPick: PathPicker,
    enabled: Boolean,
) {
    val m = state.config.editedTransactionMappings

    @Composable
    fun path(
        label: String,
        value: String,
        update: (String) -> Unit,
    ) = PathFieldRow(label, value, update, txJsonPaths, onRequestPick, enabled)

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SessionDataStatus(paths = txJsonPaths, loaded = txSampleLoaded)

        path("Amount field", m.amountField) { v -> state.updateTransactionMappings { copy(amountField = v) } }
        path("Timestamp field (ISO 8601)", m.timestampField) { v -> state.updateTransactionMappings { copy(timestampField = v) } }
        path("Currency field (ISO 4217)", m.currencyField) { v -> state.updateTransactionMappings { copy(currencyField = v) } }
        path("Description field", m.descriptionField) { v -> state.updateTransactionMappings { copy(descriptionField = v) } }
        path("Transaction ID field (de-duplication)", m.idField) { v -> state.updateTransactionMappings { copy(idField = v) } }

        EnumDropdown(
            label = "Amount format",
            options = ApiAmountFormat.entries,
            selected = m.amountFormat,
            onSelect = { v -> state.updateTransactionMappings { copy(amountFormat = v) } },
            optionLabel = { it.name },
            enabled = enabled,
        )
        path("Counterparty ID field (optional)", m.counterpartyIdField.orEmpty()) { v ->
            state.updateTransactionMappings { copy(counterpartyIdField = v.ifBlank { null }) }
        }
    }
}

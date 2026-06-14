package com.moneymanager.ui.screens.apistrategy.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.apistrategy.ApiAmountFormat
import com.moneymanager.domain.model.apistrategy.ApiSignSource
import com.moneymanager.ui.screens.apistrategy.JsonPathEntry

@Composable
internal fun TransactionMappingsTab(
    state: ApiStrategyEditorState,
    txJsonPaths: List<JsonPathEntry>,
    txSampleLoaded: Boolean,
    onRequestPick: PathPicker,
    enabled: Boolean,
) {
    val m = state.transactionMappings

    @Composable
    fun path(
        label: String,
        value: String,
        update: (String) -> Unit,
    ) = PathFieldRow(label, value, update, txJsonPaths, onRequestPick, enabled)

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SessionDataStatus(paths = txJsonPaths, loaded = txSampleLoaded)

        path("Amount field", m.amountField) { state.transactionMappings = m.copy(amountField = it) }
        path("Timestamp field (ISO 8601)", m.timestampField) { state.transactionMappings = m.copy(timestampField = it) }
        path("Currency field (ISO 4217)", m.currencyField) { state.transactionMappings = m.copy(currencyField = it) }
        path("Description field", m.descriptionField) { state.transactionMappings = m.copy(descriptionField = it) }
        path("Transaction ID field (de-duplication)", m.idField) { state.transactionMappings = m.copy(idField = it) }

        EnumDropdown(
            label = "Amount format",
            options = ApiAmountFormat.entries,
            selected = m.amountFormat,
            onSelect = { state.transactionMappings = m.copy(amountFormat = it) },
            optionLabel = { it.name },
            enabled = enabled,
        )
        EnumDropdown(
            label = "Sign source",
            options = ApiSignSource.entries,
            selected = m.signSource,
            onSelect = { state.transactionMappings = m.copy(signSource = it) },
            optionLabel = { it.name },
            enabled = enabled,
        )
        if (m.signSource == ApiSignSource.FIELD) {
            path("Sign field", m.signField.orEmpty()) { state.transactionMappings = m.copy(signField = it.ifBlank { null }) }
            StringSetEditor(
                label = "Credit values (mean incoming/positive)",
                values = m.creditValues,
                onChange = { state.transactionMappings = m.copy(creditValues = it) },
                enabled = enabled,
            )
        }

        path("Merchant name field (optional)", m.merchantNameField.orEmpty()) {
            state.transactionMappings = m.copy(merchantNameField = it.ifBlank { null })
        }
        path("Counterparty name field (optional)", m.counterpartyNameField.orEmpty()) {
            state.transactionMappings = m.copy(counterpartyNameField = it.ifBlank { null })
        }
        path("Counterparty ID field (optional)", m.counterpartyIdField.orEmpty()) {
            state.transactionMappings = m.copy(counterpartyIdField = it.ifBlank { null })
        }
        path("Decline reason field (optional)", m.declineReasonField.orEmpty()) {
            state.transactionMappings = m.copy(declineReasonField = it.ifBlank { null })
        }
        path("Decline status field (optional)", m.declineStatusField.orEmpty()) {
            state.transactionMappings = m.copy(declineStatusField = it.ifBlank { null })
        }
        StringSetEditor(
            label = "Declined status values",
            values = m.declinedStatusValues,
            onChange = { state.transactionMappings = m.copy(declinedStatusValues = it) },
            enabled = enabled,
        )
        path("Local amount field (optional)", m.localAmountField.orEmpty()) {
            state.transactionMappings = m.copy(localAmountField = it.ifBlank { null })
        }
        path("Local currency field (optional)", m.localCurrencyField.orEmpty()) {
            state.transactionMappings = m.copy(localCurrencyField = it.ifBlank { null })
        }
        path("Fee amount field (optional)", m.feeAmountField.orEmpty()) {
            state.transactionMappings = m.copy(feeAmountField = it.ifBlank { null })
        }
        path("Fee currency field (optional)", m.feeCurrencyField.orEmpty()) {
            state.transactionMappings = m.copy(feeCurrencyField = it.ifBlank { null })
        }
        path("Fee description field (optional)", m.feeDescriptionField.orEmpty()) {
            state.transactionMappings = m.copy(feeDescriptionField = it.ifBlank { null })
        }
        ToggleRow(
            label = "Fee included in amount (carve out)",
            checked = m.feeIncludedInAmount,
            onCheckedChange = { state.transactionMappings = m.copy(feeIncludedInAmount = it) },
            enabled = enabled,
        )
        CustomFieldsSection(
            fields = state.txCustomFields,
            onFieldsChange = { state.txCustomFields = it },
            paths = txJsonPaths,
            onRequestPick = onRequestPick,
            enabled = enabled,
        )
    }
}

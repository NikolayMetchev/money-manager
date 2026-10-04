package com.moneymanager.ui.screens.apistrategy.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.apistrategy.ApiAmountFormat
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.ui.components.rules.ConditionsEditor
import com.moneymanager.ui.components.rules.DirectionEditor
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
        // A bank feed's own direction is its signed amount.
        DirectionEditor(
            direction = m.direction,
            onDirectionChanged = { v -> state.updateTransactionMappings { copy(direction = v) } },
            pathField = jsonPathField(txJsonPaths, onRequestPick, enabled),
            enabled = enabled,
            allowDefault = true,
        )

        path("Merchant name field (optional)", m.merchantNameField.orEmpty()) { v ->
            state.updateTransactionMappings { copy(merchantNameField = v.ifBlank { null }) }
        }
        path("Counterparty name field (optional)", m.counterpartyNameField.orEmpty()) { v ->
            state.updateTransactionMappings { copy(counterpartyNameField = v.ifBlank { null }) }
        }
        path("Counterparty ID field (optional)", m.counterpartyIdField.orEmpty()) { v ->
            state.updateTransactionMappings { copy(counterpartyIdField = v.ifBlank { null }) }
        }
        val conditionPath = jsonPathField(txJsonPaths, onRequestPick, enabled)
        ConditionsEditor(
            title = "Declined when (the first that holds wins; its field's value is the decline reason)",
            conditions = m.declinedWhen,
            onConditionsChanged = { v -> state.updateTransactionMappings { copy(declinedWhen = v) } },
            pathField = conditionPath,
            enabled = enabled,
            newCondition = { Condition("", ConditionOp.NOT_BLANK) },
            pathLabel = "Path",
        )
        ConditionsEditor(
            title = "Only import items where (all must match; none = every item)",
            conditions = m.itemFilters,
            onConditionsChanged = { v -> state.updateTransactionMappings { copy(itemFilters = v) } },
            pathField = conditionPath,
            enabled = enabled,
            pathLabel = "Path",
        )
        ConditionsEditor(
            title = "Skip items where (all must match; none = skip nothing)",
            conditions = m.excludeWhen,
            onConditionsChanged = { v -> state.updateTransactionMappings { copy(excludeWhen = v) } },
            pathField = conditionPath,
            enabled = enabled,
            pathLabel = "Path",
        )
        path("Local amount field (optional)", m.localAmountField.orEmpty()) { v ->
            state.updateTransactionMappings { copy(localAmountField = v.ifBlank { null }) }
        }
        path("Local currency field (optional)", m.localCurrencyField.orEmpty()) { v ->
            state.updateTransactionMappings { copy(localCurrencyField = v.ifBlank { null }) }
        }
        path("Fee amount field (optional)", m.feeAmountField.orEmpty()) { v ->
            state.updateTransactionMappings { copy(feeAmountField = v.ifBlank { null }) }
        }
        path("Fee currency field (optional)", m.feeCurrencyField.orEmpty()) { v ->
            state.updateTransactionMappings { copy(feeCurrencyField = v.ifBlank { null }) }
        }
        path("Fee description field (optional)", m.feeDescriptionField.orEmpty()) { v ->
            state.updateTransactionMappings { copy(feeDescriptionField = v.ifBlank { null }) }
        }
        ToggleRow(
            label = "Fee included in amount (carve out)",
            checked = m.feeIncludedInAmount,
            onCheckedChange = { v -> state.updateTransactionMappings { copy(feeIncludedInAmount = v) } },
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

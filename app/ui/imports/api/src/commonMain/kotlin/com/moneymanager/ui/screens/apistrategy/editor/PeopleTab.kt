package com.moneymanager.ui.screens.apistrategy.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiPersonImportConfig
import com.moneymanager.ui.screens.apistrategy.JsonPathEntry

@Composable
internal fun PeopleTab(
    state: ApiStrategyEditorState,
    txJsonPaths: List<JsonPathEntry>,
    onRequestPick: PathPicker,
    enabled: Boolean,
) {
    val p = state.peopleMappings

    @Composable
    fun path(
        label: String,
        value: String,
        update: (String) -> Unit,
    ) = PathFieldRow(label, value, update, txJsonPaths, onRequestPick, enabled)

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Counterparty / people mappings")
        path("Counterparty object field", p.counterpartyObjectField) { state.peopleMappings = p.copy(counterpartyObjectField = it) }
        path("Beneficiary account-type field", p.beneficiaryAccountTypeField) {
            state.peopleMappings = p.copy(beneficiaryAccountTypeField = it)
        }
        TextFieldRow(
            label = "Personal beneficiary value",
            value = p.personalBeneficiaryAccountTypeValue,
            onValueChange = { state.peopleMappings = p.copy(personalBeneficiaryAccountTypeValue = it) },
            enabled = enabled,
        )
        StringSetEditor(
            label = "Additional personal beneficiary values",
            values = p.personalBeneficiaryAccountTypeValues,
            onChange = { state.peopleMappings = p.copy(personalBeneficiaryAccountTypeValues = it) },
            enabled = enabled,
        )
        path("Counterparty name field", p.counterpartyNameField) { state.peopleMappings = p.copy(counterpartyNameField = it) }
        path("Counterparty user-id field", p.counterpartyUserIdField) { state.peopleMappings = p.copy(counterpartyUserIdField = it) }
        path("Counterparty sort code field", p.counterpartySortCodeField) {
            state.peopleMappings = p.copy(counterpartySortCodeField = it)
        }
        path("Counterparty account number field", p.counterpartyAccountNumberField) {
            state.peopleMappings = p.copy(counterpartyAccountNumberField = it)
        }
        path("Counterparty service-user-number field", p.counterpartyServiceUserNumberField) {
            state.peopleMappings = p.copy(counterpartyServiceUserNumberField = it)
        }
        path("Counterparty account-id field", p.counterpartyAccountIdField) {
            state.peopleMappings = p.copy(counterpartyAccountIdField = it)
        }
        ToggleRow(
            label = "Prefer bank identity (sort code + account number over counterparty id)",
            checked = p.preferBankIdentity,
            onCheckedChange = { state.peopleMappings = p.copy(preferBankIdentity = it) },
            enabled = enabled,
        )

        Spacer(Modifier.padding(top = 4.dp))
        HorizontalDivider()
        SectionHeader("People download")
        Text(
            text = "Optional dedicated endpoint that imports the account holder(s) (e.g. Wise /v1/profiles).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ToggleRow(
            label = "Enable people download",
            checked = state.peopleDownload != null,
            onCheckedChange = { on ->
                state.peopleDownload =
                    if (on) {
                        ApiPersonImportConfig(
                            endpoint = ApiEndpointConfig(path = "", responseArrayKey = ""),
                            firstNameField = "",
                        )
                    } else {
                        null
                    }
            },
            enabled = enabled,
        )
        state.peopleDownload?.let { config ->
            PeopleDownloadEditor(
                config = config,
                onChange = { state.peopleDownload = it },
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun PeopleDownloadEditor(
    config: ApiPersonImportConfig,
    onChange: (ApiPersonImportConfig) -> Unit,
    enabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Endpoint", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        EndpointEditor(
            endpoint = config.endpoint,
            onChange = { onChange(config.copy(endpoint = it)) },
            enabled = enabled,
        )
        TextFieldRow("External-id field", config.externalIdField, { onChange(config.copy(externalIdField = it)) }, enabled)
        TextFieldRow(
            label = "First name field",
            value = config.firstNameField,
            onValueChange = { onChange(config.copy(firstNameField = it)) },
            enabled = enabled,
            isError = config.firstNameField.isBlank(),
        )
        TextFieldRow("Last name field (optional)", config.lastNameField.orEmpty(), {
            onChange(config.copy(lastNameField = it.ifBlank { null }))
        }, enabled)
        TextFieldRow("Preferred name field (optional)", config.preferredNameField.orEmpty(), {
            onChange(config.copy(preferredNameField = it.ifBlank { null }))
        }, enabled)
        TextFieldRow("Fallback name field (optional)", config.fallbackNameField.orEmpty(), {
            onChange(config.copy(fallbackNameField = it.ifBlank { null }))
        }, enabled)
        val ancestorExprError = config.ownsAllAccounts && !config.accountOwnerAncestorExpr.isNullOrBlank()
        TextFieldRow(
            label = "Account-owner ancestor expression (optional)",
            value = config.accountOwnerAncestorExpr.orEmpty(),
            onValueChange = { onChange(config.copy(accountOwnerAncestorExpr = it.ifBlank { null })) },
            enabled = enabled,
            isError = ancestorExprError,
            supportingText = if (ancestorExprError) "Mutually exclusive with \"owns all accounts\"" else null,
        )
        ToggleRow(
            label = "Holder owns all accounts",
            checked = config.ownsAllAccounts,
            onCheckedChange = { onChange(config.copy(ownsAllAccounts = it)) },
            enabled = enabled,
        )
    }
}

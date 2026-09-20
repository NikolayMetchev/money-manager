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
import com.moneymanager.domain.model.apistrategy.ApiPeopleMappings
import com.moneymanager.domain.model.apistrategy.ApiPersonImportConfig
import com.moneymanager.ui.screens.apistrategy.JsonPathEntry

/** Edits [ApiPeopleMappings] in place inside the strategy config. */
private fun ApiStrategyEditorState.updatePeopleMappings(block: ApiPeopleMappings.() -> ApiPeopleMappings) =
    updateConfig { copy(peopleMappings = peopleMappings.block()) }

@Composable
internal fun PeopleTab(
    state: ApiStrategyEditorState,
    txJsonPaths: List<JsonPathEntry>,
    onRequestPick: PathPicker,
    enabled: Boolean,
) {
    val p = state.config.peopleMappings

    @Composable
    fun path(
        label: String,
        value: String,
        update: (String) -> Unit,
    ) = PathFieldRow(label, value, update, txJsonPaths, onRequestPick, enabled)

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Counterparty / people mappings")
        path("Counterparty object field", p.counterpartyObjectField) { v ->
            state.updatePeopleMappings { copy(counterpartyObjectField = v) }
        }
        path("Beneficiary account-type field", p.beneficiaryAccountTypeField) { v ->
            state.updatePeopleMappings { copy(beneficiaryAccountTypeField = v) }
        }
        TextFieldRow(
            label = "Personal beneficiary value",
            value = p.personalBeneficiaryAccountTypeValue,
            onValueChange = { v -> state.updatePeopleMappings { copy(personalBeneficiaryAccountTypeValue = v) } },
            enabled = enabled,
        )
        StringSetEditor(
            label = "Additional personal beneficiary values",
            values = p.personalBeneficiaryAccountTypeValues,
            onChange = { v -> state.updatePeopleMappings { copy(personalBeneficiaryAccountTypeValues = v) } },
            enabled = enabled,
        )
        path("Counterparty name field", p.counterpartyNameField) { v -> state.updatePeopleMappings { copy(counterpartyNameField = v) } }
        path("Counterparty user-id field", p.counterpartyUserIdField) { v ->
            state.updatePeopleMappings { copy(counterpartyUserIdField = v) }
        }
        path("Counterparty sort code field", p.counterpartySortCodeField) { v ->
            state.updatePeopleMappings { copy(counterpartySortCodeField = v) }
        }
        path("Counterparty account number field", p.counterpartyAccountNumberField) { v ->
            state.updatePeopleMappings { copy(counterpartyAccountNumberField = v) }
        }
        path("Counterparty service-user-number field", p.counterpartyServiceUserNumberField) { v ->
            state.updatePeopleMappings { copy(counterpartyServiceUserNumberField = v) }
        }
        path("Counterparty account-id field", p.counterpartyAccountIdField) { v ->
            state.updatePeopleMappings { copy(counterpartyAccountIdField = v) }
        }
        ToggleRow(
            label = "Prefer bank identity (sort code + account number over counterparty id)",
            checked = p.preferBankIdentity,
            onCheckedChange = { v -> state.updatePeopleMappings { copy(preferBankIdentity = v) } },
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
            checked = state.config.peopleDownload != null,
            onCheckedChange = { on ->
                state.updateConfig {
                    copy(
                        peopleDownload =
                            if (on) {
                                ApiPersonImportConfig(
                                    endpoint = ApiEndpointConfig(path = "", responseArrayKey = ""),
                                    firstNameField = "",
                                )
                            } else {
                                null
                            },
                    )
                }
            },
            enabled = enabled,
        )
        state.config.peopleDownload?.let { config ->
            PeopleDownloadEditor(
                config = config,
                onChange = { v -> state.updateConfig { copy(peopleDownload = v) } },
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

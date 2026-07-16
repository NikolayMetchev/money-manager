package com.moneymanager.ui.screens.apistrategy.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig

@Composable
internal fun EndpointsTab(
    state: ApiStrategyEditorState,
    enabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeader("Synthetic account")
        Text(
            text =
                "For exchanges that hold all assets in one account instead of an accounts endpoint. " +
                    "When enabled, the accounts endpoint below is not fetched.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SyntheticAccountEditor(
            account = state.syntheticAccount,
            onChange = { state.syntheticAccount = it },
            enabled = enabled,
        )

        Spacer(Modifier.padding(top = 4.dp))
        HorizontalDivider()
        SectionHeader("Accounts endpoint")
        EndpointEditor(
            endpoint = state.accountsEndpoint,
            onChange = { state.accountsEndpoint = it },
            enabled = enabled,
        )

        Spacer(Modifier.padding(top = 4.dp))
        HorizontalDivider()
        SectionHeader("Transactions endpoint")
        EndpointEditor(
            endpoint = state.transactionsEndpoint,
            onChange = { state.transactionsEndpoint = it },
            enabled = enabled,
        )

        Spacer(Modifier.padding(top = 4.dp))
        HorizontalDivider()
        SectionHeader("Account-identifiers endpoint")
        Text(
            text =
                "Optional per-account endpoint returning the account's own sort code / account number " +
                    "(e.g. Starling's /accounts/{account.id}/identifiers).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ToggleRow(
            label = "Enable account-identifiers endpoint",
            checked = state.accountIdentifiersEndpoint != null,
            onCheckedChange = { on ->
                state.accountIdentifiersEndpoint =
                    if (on) {
                        ApiEndpointConfig(path = "/accounts/{account.id}/identifiers", responseArrayKey = "")
                    } else {
                        null
                    }
            },
            enabled = enabled,
        )
        state.accountIdentifiersEndpoint?.let { endpoint ->
            EndpointEditor(
                endpoint = endpoint,
                onChange = { state.accountIdentifiersEndpoint = it },
                enabled = enabled,
            )
        }

        Spacer(Modifier.padding(top = 4.dp))
        HorizontalDivider()
        SectionHeader("Ancestor endpoints")
        Text(
            text =
                "Resource endpoints fetched before accounts whose items supply ids/fields for " +
                    "templating (e.g. Wise profiles). Order matters: referenced as ancestor[0], ancestor[1]…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.ancestorEndpoints.forEachIndexed { index, endpoint ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors()) {
                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    EditorCardHeader(
                        title = "ancestor[$index]",
                        onRemove = { state.ancestorEndpoints = state.ancestorEndpoints.toMutableList().also { it.removeAt(index) } },
                        enabled = enabled,
                    )
                    EndpointEditor(
                        endpoint = endpoint,
                        onChange = { updated ->
                            state.ancestorEndpoints = state.ancestorEndpoints.toMutableList().also { it[index] = updated }
                        },
                        enabled = enabled,
                    )
                }
            }
        }
        TextButton(
            onClick = {
                state.ancestorEndpoints += ApiEndpointConfig(path = "", responseArrayKey = "")
            },
            enabled = enabled,
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Add ancestor endpoint")
        }

        Spacer(Modifier.padding(top = 4.dp))
        HorizontalDivider()
        SectionHeader("Data endpoints")
        Text(
            text =
                "Additional endpoints an exchange exposes (trades, orders, deposits, withdrawals), each " +
                    "producing a different kind of record.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DataEndpointsEditor(
            endpoints = state.dataEndpoints,
            onChange = { state.dataEndpoints = it },
            enabled = enabled,
        )
    }
}

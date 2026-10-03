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
import com.moneymanager.domain.model.apistrategy.ApiAccountsSource
import com.moneymanager.domain.model.apistrategy.ApiDataEndpoint
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiEndpointKind
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings

@Composable
internal fun EndpointsTab(
    state: ApiStrategyEditorState,
    enabled: Boolean,
) {
    val accounts = state.config.accounts
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeader("Accounts")
        Text(
            text =
                "Banks list their accounts from an endpoint and fetch a transaction feed per account; exchanges " +
                    "hold every asset in one account and fetch their data endpoints instead.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ToggleRow(
            label = "Single account (exchanges)",
            checked = accounts is ApiAccountsSource.Single,
            onCheckedChange = { single ->
                state.updateConfig {
                    if (single) {
                        copy(
                            accounts = ApiAccountsSource.Single(name = "", externalId = ""),
                            dataEndpoints = dataEndpoints.filter { it.kind != ApiEndpointKind.BANK_TRANSACTIONS },
                        )
                    } else {
                        copy(
                            accounts =
                                ApiAccountsSource.Downloaded(endpoint = DEFAULT_ACCOUNTS_ENDPOINT),
                            dataEndpoints =
                                dataEndpoints +
                                    ApiDataEndpoint(
                                        endpoint = DEFAULT_TRANSACTIONS_ENDPOINT,
                                        kind = ApiEndpointKind.BANK_TRANSACTIONS,
                                        transactionMappings = ApiTransactionMappings(),
                                    ),
                        )
                    }
                }
            },
            enabled = enabled,
        )
        when (accounts) {
            is ApiAccountsSource.Single ->
                SingleAccountEditor(a = accounts, onChange = { v -> state.updateConfig { copy(accounts = v) } }, enabled = enabled)
            is ApiAccountsSource.Downloaded -> DownloadedAccountsEditor(state, accounts, enabled)
        }

        Spacer(Modifier.padding(top = 4.dp))
        HorizontalDivider()
        SectionHeader("Data endpoints")
        Text(
            text =
                "Further endpoints whose items are imported (an exchange's trades, orders, deposits, withdrawals), " +
                    "each producing a different kind of record.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val bankFeed = state.config.dataEndpoints.filter { it.kind == ApiEndpointKind.BANK_TRANSACTIONS }
        DataEndpointsEditor(
            endpoints = state.config.dataEndpoints.filter { it.kind != ApiEndpointKind.BANK_TRANSACTIONS },
            onChange = { v -> state.updateConfig { copy(dataEndpoints = bankFeed + v) } },
            enabled = enabled,
        )
    }
}

/** The accounts endpoint, identifiers and ancestors of a bank strategy, plus its transaction feed endpoint. */
@Composable
private fun DownloadedAccountsEditor(
    state: ApiStrategyEditorState,
    accounts: ApiAccountsSource.Downloaded,
    enabled: Boolean,
) {
    fun update(block: ApiAccountsSource.Downloaded.() -> ApiAccountsSource.Downloaded) =
        state.updateConfig { copy(accounts = (this.accounts as ApiAccountsSource.Downloaded).block()) }

    SectionHeader("Accounts endpoint")
    EndpointEditor(endpoint = accounts.endpoint, onChange = { v -> update { copy(endpoint = v) } }, enabled = enabled)

    state.config.bankTransactions?.let { feed ->
        Spacer(Modifier.padding(top = 4.dp))
        HorizontalDivider()
        SectionHeader("Transactions endpoint (fetched per account)")
        EndpointEditor(
            endpoint = feed.endpoint,
            onChange = { v ->
                state.updateConfig {
                    copy(
                        dataEndpoints =
                            dataEndpoints.map {
                                if (it.kind ==
                                    ApiEndpointKind.BANK_TRANSACTIONS
                                ) {
                                    it.copy(endpoint = v)
                                } else {
                                    it
                                }
                            },
                    )
                }
            },
            enabled = enabled,
        )
    }

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
        checked = accounts.identifiersEndpoint != null,
        onCheckedChange = { on ->
            update {
                copy(
                    identifiersEndpoint =
                        if (on) {
                            ApiEndpointConfig(
                                path = "/accounts/{account.id}/identifiers",
                                responseArrayKey = "",
                            )
                        } else {
                            null
                        },
                )
            }
        },
        enabled = enabled,
    )
    accounts.identifiersEndpoint?.let { endpoint ->
        EndpointEditor(endpoint = endpoint, onChange = { v -> update { copy(identifiersEndpoint = v) } }, enabled = enabled)
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
    accounts.ancestorEndpoints.forEachIndexed { index, endpoint ->
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors()) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EditorCardHeader(
                    title = "ancestor[$index]",
                    onRemove = { update { copy(ancestorEndpoints = ancestorEndpoints.minusAt(index)) } },
                    enabled = enabled,
                )
                EndpointEditor(
                    endpoint = endpoint,
                    onChange = { updated -> update { copy(ancestorEndpoints = ancestorEndpoints.replacingAt(index, updated)) } },
                    enabled = enabled,
                )
            }
        }
    }
    TextButton(
        onClick = { update { copy(ancestorEndpoints = ancestorEndpoints + ApiEndpointConfig(path = "", responseArrayKey = "")) } },
        enabled = enabled,
    ) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.width(4.dp))
        Text("Add ancestor endpoint")
    }
}

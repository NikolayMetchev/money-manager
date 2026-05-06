@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    kotlin.uuid.ExperimentalUuidApi::class,
)

package com.moneymanager.ui.screens.apistrategy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.moneymanager.domain.model.apistrategy.ApiAccountMappings
import com.moneymanager.domain.model.apistrategy.ApiAuthType
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId
import com.moneymanager.domain.model.apistrategy.ApiPaginationConfig
import com.moneymanager.domain.model.apistrategy.ApiQueryParam
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Dialog for creating or editing an [ApiImportStrategy].
 *
 * @param strategy Existing strategy to edit, or null when creating a new one.
 * @param onSave Called with the new/updated strategy when the user confirms.
 * @param onDismiss Called when the dialog is cancelled.
 */
@Composable
fun ApiStrategyEditDialog(
    strategy: ApiImportStrategy?,
    onSave: (ApiImportStrategy) -> Unit,
    onDismiss: () -> Unit,
) {
    val isNew = strategy == null

    // General
    var name by remember { mutableStateOf(strategy?.name ?: "") }
    var baseUrl by remember { mutableStateOf(strategy?.baseUrl ?: "") }
    var accountNamePrefix by remember { mutableStateOf(strategy?.accountNamePrefix ?: "") }
    var counterpartyPrefix by remember { mutableStateOf(strategy?.counterpartyPrefix ?: "") }

    // Accounts endpoint
    var accountsPath by remember { mutableStateOf(strategy?.accountsEndpoint?.path ?: "/accounts") }
    var accountsResponseArrayKey by remember { mutableStateOf(strategy?.accountsEndpoint?.responseArrayKey ?: "accounts") }

    // Transactions endpoint
    var transactionsPath by remember { mutableStateOf(strategy?.transactionsEndpoint?.path ?: "/transactions") }
    var transactionsResponseArrayKey by remember { mutableStateOf(strategy?.transactionsEndpoint?.responseArrayKey ?: "transactions") }
    var transactionsAccountIdParam by remember {
        mutableStateOf(
            strategy?.transactionsEndpoint?.queryParams
                ?.firstOrNull { it.dynamicSource == "account.id" }
                ?.name ?: "account_id",
        )
    }

    // Pagination
    var paginationEnabled by remember {
        mutableStateOf(strategy?.transactionsEndpoint?.pagination != null)
    }
    val defaultPagination = strategy?.transactionsEndpoint?.pagination ?: ApiPaginationConfig()
    var paginationLimitParam by remember { mutableStateOf(defaultPagination.limitParam) }
    var paginationLimitValue by remember { mutableStateOf(defaultPagination.limitValue.toString()) }
    var paginationCursorParam by remember { mutableStateOf(defaultPagination.cursorParam) }
    var paginationCursorField by remember { mutableStateOf(defaultPagination.cursorResponseField) }

    // Account field mappings
    var accountIdField by remember { mutableStateOf(strategy?.accountMappings?.idField ?: "id") }
    var accountDescriptionField by remember { mutableStateOf(strategy?.accountMappings?.descriptionField ?: "description") }
    var accountOwnerNameField by remember { mutableStateOf(strategy?.accountMappings?.ownerNameField ?: "") }

    // Transaction field mappings
    var txAmountField by remember { mutableStateOf(strategy?.transactionMappings?.amountField ?: "amount") }
    var txTimestampField by remember { mutableStateOf(strategy?.transactionMappings?.timestampField ?: "created") }
    var txCurrencyField by remember { mutableStateOf(strategy?.transactionMappings?.currencyField ?: "currency") }
    var txDescriptionField by remember { mutableStateOf(strategy?.transactionMappings?.descriptionField ?: "description") }
    var txMerchantNameField by remember { mutableStateOf(strategy?.transactionMappings?.merchantNameField ?: "") }
    var txCounterpartyNameField by remember { mutableStateOf(strategy?.transactionMappings?.counterpartyNameField ?: "") }
    var txDeclineReasonField by remember { mutableStateOf(strategy?.transactionMappings?.declineReasonField ?: "") }

    val isValid = name.isNotBlank() && baseUrl.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New API Import Strategy" else "Edit Strategy") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 500.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // General settings
                SectionHeader("General")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Strategy Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    placeholder = { Text("https://api.example.com") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = accountNamePrefix,
                    onValueChange = { accountNamePrefix = it },
                    label = { Text("Account Name Prefix") },
                    placeholder = { Text("e.g. Monzo: ") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = counterpartyPrefix,
                    onValueChange = { counterpartyPrefix = it },
                    label = { Text("Counterparty Account Prefix") },
                    placeholder = { Text("e.g. Monzo Counterparty: ") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Spacer(Modifier.height(4.dp))
                HorizontalDivider()

                // Accounts endpoint
                SectionHeader("Accounts Endpoint")
                OutlinedTextField(
                    value = accountsPath,
                    onValueChange = { accountsPath = it },
                    label = { Text("Path") },
                    placeholder = { Text("/accounts") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = accountsResponseArrayKey,
                    onValueChange = { accountsResponseArrayKey = it },
                    label = { Text("Response Array Key") },
                    placeholder = { Text("accounts") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Spacer(Modifier.height(4.dp))
                HorizontalDivider()

                // Transactions endpoint
                SectionHeader("Transactions Endpoint")
                OutlinedTextField(
                    value = transactionsPath,
                    onValueChange = { transactionsPath = it },
                    label = { Text("Path") },
                    placeholder = { Text("/transactions") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = transactionsResponseArrayKey,
                    onValueChange = { transactionsResponseArrayKey = it },
                    label = { Text("Response Array Key") },
                    placeholder = { Text("transactions") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = transactionsAccountIdParam,
                    onValueChange = { transactionsAccountIdParam = it },
                    label = { Text("Account ID Query Param") },
                    placeholder = { Text("account_id") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                // Pagination
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Switch(
                        checked = paginationEnabled,
                        onCheckedChange = { paginationEnabled = it },
                    )
                    Text(
                        text = "Enable Pagination",
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                if (paginationEnabled) {
                    OutlinedTextField(
                        value = paginationLimitParam,
                        onValueChange = { paginationLimitParam = it },
                        label = { Text("Limit Param") },
                        placeholder = { Text("limit") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = paginationLimitValue,
                        onValueChange = { paginationLimitValue = it },
                        label = { Text("Limit Value") },
                        placeholder = { Text("100") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = paginationCursorParam,
                        onValueChange = { paginationCursorParam = it },
                        label = { Text("Cursor Param") },
                        placeholder = { Text("before") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = paginationCursorField,
                        onValueChange = { paginationCursorField = it },
                        label = { Text("Cursor Response Field") },
                        placeholder = { Text("created") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }

                Spacer(Modifier.height(4.dp))
                HorizontalDivider()

                // Account field mappings
                SectionHeader("Account Field Mappings")
                OutlinedTextField(
                    value = accountIdField,
                    onValueChange = { accountIdField = it },
                    label = { Text("Account ID Field") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = accountDescriptionField,
                    onValueChange = { accountDescriptionField = it },
                    label = { Text("Account Description Field") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = accountOwnerNameField,
                    onValueChange = { accountOwnerNameField = it },
                    label = { Text("Owner Name Field (optional)") },
                    placeholder = { Text("preferred_name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Spacer(Modifier.height(4.dp))
                HorizontalDivider()

                // Transaction field mappings
                SectionHeader("Transaction Field Mappings")
                OutlinedTextField(
                    value = txAmountField,
                    onValueChange = { txAmountField = it },
                    label = { Text("Amount Field (minor units)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = txTimestampField,
                    onValueChange = { txTimestampField = it },
                    label = { Text("Timestamp Field (ISO 8601)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = txCurrencyField,
                    onValueChange = { txCurrencyField = it },
                    label = { Text("Currency Field (ISO 4217 code)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = txDescriptionField,
                    onValueChange = { txDescriptionField = it },
                    label = { Text("Description Field") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = txMerchantNameField,
                    onValueChange = { txMerchantNameField = it },
                    label = { Text("Merchant Name Field (optional, dot-notation)") },
                    placeholder = { Text("merchant.name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = txCounterpartyNameField,
                    onValueChange = { txCounterpartyNameField = it },
                    label = { Text("Counterparty Name Field (optional, dot-notation)") },
                    placeholder = { Text("counterparty.name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = txDeclineReasonField,
                    onValueChange = { txDeclineReasonField = it },
                    label = { Text("Decline Reason Field (optional)") },
                    placeholder = { Text("decline_reason") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val now = Clock.System.now()
                    val pagination =
                        if (paginationEnabled) {
                            ApiPaginationConfig(
                                limitParam = paginationLimitParam,
                                limitValue = paginationLimitValue.toIntOrNull() ?: 100,
                                cursorParam = paginationCursorParam,
                                cursorResponseField = paginationCursorField,
                            )
                        } else {
                            null
                        }
                    val accountIdQueryParam =
                        if (transactionsAccountIdParam.isNotBlank()) {
                            listOf(ApiQueryParam(name = transactionsAccountIdParam, dynamicSource = "account.id"))
                        } else {
                            emptyList()
                        }
                    val saved =
                        ApiImportStrategy(
                            id = strategy?.id ?: ApiImportStrategyId(Uuid.random()),
                            name = name.trim(),
                            baseUrl = baseUrl.trim(),
                            authType = ApiAuthType.BEARER_TOKEN,
                            accountsEndpoint =
                                ApiEndpointConfig(
                                    path = accountsPath.trim(),
                                    responseArrayKey = accountsResponseArrayKey.trim(),
                                ),
                            transactionsEndpoint =
                                ApiEndpointConfig(
                                    path = transactionsPath.trim(),
                                    responseArrayKey = transactionsResponseArrayKey.trim(),
                                    queryParams = accountIdQueryParam,
                                    pagination = pagination,
                                ),
                            accountMappings =
                                ApiAccountMappings(
                                    idField = accountIdField.trim(),
                                    descriptionField = accountDescriptionField.trim(),
                                    ownerNameField = accountOwnerNameField.trim().ifBlank { null },
                                ),
                            transactionMappings =
                                ApiTransactionMappings(
                                    amountField = txAmountField.trim(),
                                    timestampField = txTimestampField.trim(),
                                    currencyField = txCurrencyField.trim(),
                                    descriptionField = txDescriptionField.trim(),
                                    merchantNameField = txMerchantNameField.trim().ifBlank { null },
                                    counterpartyNameField = txCounterpartyNameField.trim().ifBlank { null },
                                    declineReasonField = txDeclineReasonField.trim().ifBlank { null },
                                ),
                            accountNamePrefix = accountNamePrefix.trim(),
                            counterpartyPrefix = counterpartyPrefix.trim(),
                            createdAt = strategy?.createdAt ?: now,
                            updatedAt = now,
                        )
                    onSave(saved)
                },
                enabled = isValid,
            ) {
                Text(if (isNew) "Create" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

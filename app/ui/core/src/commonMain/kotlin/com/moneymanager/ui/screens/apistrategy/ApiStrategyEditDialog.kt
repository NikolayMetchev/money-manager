@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    kotlin.uuid.ExperimentalUuidApi::class,
)

package com.moneymanager.ui.screens.apistrategy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.ApiSessionKind
import com.moneymanager.domain.model.apistrategy.ApiAccountMappings
import com.moneymanager.domain.model.apistrategy.ApiAuthType
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId
import com.moneymanager.domain.model.apistrategy.ApiPaginationConfig
import com.moneymanager.domain.model.apistrategy.ApiQueryParam
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.repository.ApiSessionRepository
import kotlin.time.Clock
import kotlin.uuid.Uuid

@Composable
fun ApiStrategyEditDialog(
    strategy: ApiImportStrategy?,
    apiSessionRepository: ApiSessionRepository,
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
            strategy
                ?.transactionsEndpoint
                ?.queryParams
                ?.firstOrNull { it.dynamicSource == "account.id" }
                ?.name ?: "account_id",
        )
    }

    // Pagination
    var paginationEnabled by remember { mutableStateOf(strategy?.transactionsEndpoint?.pagination != null) }
    val defaultPagination = strategy?.transactionsEndpoint?.pagination ?: ApiPaginationConfig()
    var paginationLimitParam by remember { mutableStateOf(defaultPagination.limitParam) }
    var paginationLimitValue by remember { mutableStateOf(defaultPagination.limitValue.toString()) }
    var paginationCursorParam by remember { mutableStateOf(defaultPagination.cursorParam) }
    var paginationCursorField by remember { mutableStateOf(defaultPagination.cursorResponseField) }

    // Account field mappings
    var accountIdField by remember { mutableStateOf(strategy?.accountMappings?.idField ?: "id") }
    var accountDescriptionField by remember { mutableStateOf(strategy?.accountMappings?.descriptionField ?: "description") }
    var accountOwnerNameField by remember { mutableStateOf(strategy?.accountMappings?.ownerNameField ?: "") }
    var customAccountFields by remember {
        mutableStateOf<List<CustomFieldState>>(
            strategy?.accountMappings?.customFields?.map { (k, v) ->
                CustomFieldState(k, v, k in (strategy.accountMappings.uniqueIdentifierFields))
            } ?: emptyList(),
        )
    }

    // Transaction field mappings
    var txAmountField by remember { mutableStateOf(strategy?.transactionMappings?.amountField ?: "amount") }
    var txTimestampField by remember { mutableStateOf(strategy?.transactionMappings?.timestampField ?: "created") }
    var txCurrencyField by remember { mutableStateOf(strategy?.transactionMappings?.currencyField ?: "currency") }
    var txDescriptionField by remember { mutableStateOf(strategy?.transactionMappings?.descriptionField ?: "description") }
    var txMerchantNameField by remember { mutableStateOf(strategy?.transactionMappings?.merchantNameField ?: "") }
    var txCounterpartyNameField by remember { mutableStateOf(strategy?.transactionMappings?.counterpartyNameField ?: "") }
    var txCounterpartyIdField by remember { mutableStateOf(strategy?.transactionMappings?.counterpartyIdField ?: "") }
    var txDeclineReasonField by remember { mutableStateOf(strategy?.transactionMappings?.declineReasonField ?: "") }
    var peopleCounterpartyObjectField by remember { mutableStateOf(strategy?.peopleMappings?.counterpartyObjectField ?: "counterparty") }
    var peopleBeneficiaryAccountTypeField by remember {
        mutableStateOf(
            strategy?.peopleMappings?.beneficiaryAccountTypeField ?: "beneficiary_account_type",
        )
    }
    var peoplePersonalBeneficiaryAccountTypeValue by remember {
        mutableStateOf(
            strategy?.peopleMappings?.personalBeneficiaryAccountTypeValue ?: "Personal",
        )
    }
    var peopleCounterpartyNameField by remember { mutableStateOf(strategy?.peopleMappings?.counterpartyNameField ?: "name") }
    var peopleCounterpartyUserIdField by remember { mutableStateOf(strategy?.peopleMappings?.counterpartyUserIdField ?: "user_id") }
    var peopleCounterpartySortCodeField by remember { mutableStateOf(strategy?.peopleMappings?.counterpartySortCodeField ?: "sort_code") }
    var peopleCounterpartyAccountNumberField by remember {
        mutableStateOf(
            strategy?.peopleMappings?.counterpartyAccountNumberField ?: "account_number",
        )
    }
    var peopleCounterpartyServiceUserNumberField by remember {
        mutableStateOf(
            strategy?.peopleMappings?.counterpartyServiceUserNumberField ?: "service_user_number",
        )
    }
    var peopleFallbackCounterpartyAccountIdSuffix by remember {
        mutableStateOf(
            strategy?.peopleMappings?.fallbackCounterpartyAccountIdSuffix ?: ".account_id",
        )
    }
    var customTxFields by remember {
        mutableStateOf<List<CustomFieldState>>(
            strategy?.transactionMappings?.customFields?.map { (k, v) ->
                CustomFieldState(k, v, k in (strategy.transactionMappings.uniqueIdentifierFields))
            } ?: emptyList(),
        )
    }

    // Sample JSON items loaded from past sessions — null = not loaded yet, empty = none found
    var accountSampleItem by remember { mutableStateOf<String?>(null) }
    var txSampleItem by remember { mutableStateOf<String?>(null) }

    val accountJsonPaths =
        remember(accountSampleItem) {
            accountSampleItem?.let { extractJsonPaths(it) } ?: emptyList()
        }
    val txJsonPaths =
        remember(txSampleItem) {
            txSampleItem?.let { extractJsonPaths(it) } ?: emptyList()
        }

    // Load sample JSON from the most recent session responses.
    // Prefer credentials linked to this strategy; fall back to all credentials so
    // existing Monzo credentials (created before the strategy was linked) still work.
    LaunchedEffect(strategy?.id, accountsResponseArrayKey, transactionsResponseArrayKey) {
        val allCredentials = apiSessionRepository.getAllCredentials()
        val credentials =
            if (strategy != null) {
                allCredentials.filter { it.strategyId == strategy.id }.ifEmpty { allCredentials }
            } else {
                allCredentials
            }
        for (credential in credentials) {
            val sessions = apiSessionRepository.getSessionsByCredential(credential.id)
            if (accountSampleItem == null) {
                sessions
                    .filter { it.kind == ApiSessionKind.ACCOUNTS }
                    .maxByOrNull { it.createdAt }
                    ?.let { session ->
                        for (response in apiSessionRepository.getResponsesBySession(session.id)) {
                            val item = extractFirstArrayItem(response.json, accountsResponseArrayKey)
                            if (item != null) {
                                accountSampleItem = item
                                break
                            }
                        }
                    }
            }
            if (txSampleItem == null) {
                sessions
                    .filter { it.kind == ApiSessionKind.TRANSACTIONS }
                    .maxByOrNull { it.createdAt }
                    ?.let { session ->
                        for (response in apiSessionRepository.getResponsesBySession(session.id)) {
                            val item = extractFirstArrayItem(response.json, transactionsResponseArrayKey)
                            if (item != null) {
                                txSampleItem = item
                                break
                            }
                        }
                    }
            }
            if (accountSampleItem != null && txSampleItem != null) break
        }
        // Mark as "searched but nothing found" so we don't show a loading state forever
        if (accountSampleItem == null) accountSampleItem = ""
        if (txSampleItem == null) txSampleItem = ""
    }

    // Which field's setter is currently awaiting a path pick
    var pickingForSetter by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var pickingPaths by remember { mutableStateOf<List<JsonPathEntry>>(emptyList()) }

    val isValid =
        name.isNotBlank() &&
            baseUrl.isNotBlank() &&
            (!paginationEnabled || paginationLimitValue.toIntOrNull()?.let { it > 0 } == true)

    pickingForSetter?.let { setter ->
        JsonNodePickerDialog(
            paths = pickingPaths,
            onPick = { path ->
                setter(path)
                pickingForSetter = null
            },
            onDismiss = { pickingForSetter = null },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New API Import Strategy" else "Edit Strategy") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Switch(checked = paginationEnabled, onCheckedChange = { paginationEnabled = it })
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
                        isError = paginationLimitValue.toIntOrNull() == null,
                        supportingText =
                            if (paginationLimitValue.toIntOrNull() == null) {
                                { Text("Must be a whole number") }
                            } else {
                                null
                            },
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

                SectionHeader("Account Field Mappings")
                SessionDataStatus(paths = accountJsonPaths, loaded = accountSampleItem != null)
                FieldMappingRow(
                    label = "Account ID",
                    value = accountIdField,
                    onValueChange = { accountIdField = it },
                    paths = accountJsonPaths,
                    onPickRequest = { setter ->
                        pickingPaths = accountJsonPaths
                        pickingForSetter = setter
                    },
                )
                FieldMappingRow(
                    label = "Description",
                    value = accountDescriptionField,
                    onValueChange = { accountDescriptionField = it },
                    paths = accountJsonPaths,
                    onPickRequest = { setter ->
                        pickingPaths = accountJsonPaths
                        pickingForSetter = setter
                    },
                )
                FieldMappingRow(
                    label = "Owner Name (optional)",
                    value = accountOwnerNameField,
                    onValueChange = { accountOwnerNameField = it },
                    paths = accountJsonPaths,
                    onPickRequest = { setter ->
                        pickingPaths = accountJsonPaths
                        pickingForSetter = setter
                    },
                )
                CustomFieldsSection(
                    fields = customAccountFields,
                    onFieldsChange = { customAccountFields = it },
                    paths = accountJsonPaths,
                    onPickRequest = { setter ->
                        pickingPaths = accountJsonPaths
                        pickingForSetter = setter
                    },
                )

                Spacer(Modifier.height(4.dp))
                HorizontalDivider()

                SectionHeader("Transaction Field Mappings")
                SessionDataStatus(paths = txJsonPaths, loaded = txSampleItem != null)
                FieldMappingRow(
                    label = "Amount (minor units)",
                    value = txAmountField,
                    onValueChange = { txAmountField = it },
                    paths = txJsonPaths,
                    onPickRequest = { setter ->
                        pickingPaths = txJsonPaths
                        pickingForSetter = setter
                    },
                )
                FieldMappingRow(
                    label = "Timestamp (ISO 8601)",
                    value = txTimestampField,
                    onValueChange = { txTimestampField = it },
                    paths = txJsonPaths,
                    onPickRequest = { setter ->
                        pickingPaths = txJsonPaths
                        pickingForSetter = setter
                    },
                )
                FieldMappingRow(
                    label = "Currency (ISO 4217)",
                    value = txCurrencyField,
                    onValueChange = { txCurrencyField = it },
                    paths = txJsonPaths,
                    onPickRequest = { setter ->
                        pickingPaths = txJsonPaths
                        pickingForSetter = setter
                    },
                )
                FieldMappingRow(
                    label = "Description",
                    value = txDescriptionField,
                    onValueChange = { txDescriptionField = it },
                    paths = txJsonPaths,
                    onPickRequest = { setter ->
                        pickingPaths = txJsonPaths
                        pickingForSetter = setter
                    },
                )
                FieldMappingRow(
                    label = "Merchant Name (optional, dot-notation)",
                    value = txMerchantNameField,
                    onValueChange = { txMerchantNameField = it },
                    paths = txJsonPaths,
                    onPickRequest = { setter ->
                        pickingPaths = txJsonPaths
                        pickingForSetter = setter
                    },
                )
                FieldMappingRow(
                    label = "Counterparty Name (optional, dot-notation)",
                    value = txCounterpartyNameField,
                    onValueChange = { txCounterpartyNameField = it },
                    paths = txJsonPaths,
                    onPickRequest = { setter ->
                        pickingPaths = txJsonPaths
                        pickingForSetter = setter
                    },
                )
                FieldMappingRow(
                    label = "Counterparty ID (optional, dot-notation)",
                    value = txCounterpartyIdField,
                    onValueChange = { txCounterpartyIdField = it },
                    paths = txJsonPaths,
                    onPickRequest = { setter ->
                        pickingPaths = txJsonPaths
                        pickingForSetter = setter
                    },
                )
                FieldMappingRow(
                    label = "Decline Reason (optional)",
                    value = txDeclineReasonField,
                    onValueChange = { txDeclineReasonField = it },
                    paths = txJsonPaths,
                    onPickRequest = { setter ->
                        pickingPaths = txJsonPaths
                        pickingForSetter = setter
                    },
                )
                Spacer(Modifier.height(4.dp))
                HorizontalDivider()
                SectionHeader("People Mappings")
                FieldMappingRow(label = "Counterparty object field", value = peopleCounterpartyObjectField, onValueChange = {
                    peopleCounterpartyObjectField =
                        it
                }, paths = txJsonPaths, onPickRequest = { setter ->
                    pickingPaths = txJsonPaths
                    pickingForSetter = setter
                })
                FieldMappingRow(label = "Beneficiary account type field", value = peopleBeneficiaryAccountTypeField, onValueChange = {
                    peopleBeneficiaryAccountTypeField =
                        it
                }, paths = txJsonPaths, onPickRequest = { setter ->
                    pickingPaths = txJsonPaths
                    pickingForSetter = setter
                })
                FieldMappingRow(label = "Personal beneficiary value", value = peoplePersonalBeneficiaryAccountTypeValue, onValueChange = {
                    peoplePersonalBeneficiaryAccountTypeValue =
                        it
                }, paths = emptyList(), onPickRequest = {})
                FieldMappingRow(label = "Counterparty name field", value = peopleCounterpartyNameField, onValueChange = {
                    peopleCounterpartyNameField =
                        it
                }, paths = txJsonPaths, onPickRequest = { setter ->
                    pickingPaths = txJsonPaths
                    pickingForSetter = setter
                })
                FieldMappingRow(label = "Counterparty user id field", value = peopleCounterpartyUserIdField, onValueChange = {
                    peopleCounterpartyUserIdField =
                        it
                }, paths = txJsonPaths, onPickRequest = { setter ->
                    pickingPaths = txJsonPaths
                    pickingForSetter = setter
                })
                FieldMappingRow(label = "Counterparty sort code field", value = peopleCounterpartySortCodeField, onValueChange = {
                    peopleCounterpartySortCodeField =
                        it
                }, paths = txJsonPaths, onPickRequest = { setter ->
                    pickingPaths = txJsonPaths
                    pickingForSetter = setter
                })
                FieldMappingRow(label = "Counterparty account number field", value = peopleCounterpartyAccountNumberField, onValueChange = {
                    peopleCounterpartyAccountNumberField =
                        it
                }, paths = txJsonPaths, onPickRequest = { setter ->
                    pickingPaths = txJsonPaths
                    pickingForSetter = setter
                })
                FieldMappingRow(label = "Counterparty service user number field", value = peopleCounterpartyServiceUserNumberField, onValueChange = {
                    peopleCounterpartyServiceUserNumberField =
                        it
                }, paths = txJsonPaths, onPickRequest = { setter ->
                    pickingPaths = txJsonPaths
                    pickingForSetter = setter
                })
                FieldMappingRow(label = "Fallback account id suffix", value = peopleFallbackCounterpartyAccountIdSuffix, onValueChange = {
                    peopleFallbackCounterpartyAccountIdSuffix =
                        it
                }, paths = emptyList(), onPickRequest = {})
                CustomFieldsSection(
                    fields = customTxFields,
                    onFieldsChange = { customTxFields = it },
                    paths = txJsonPaths,
                    onPickRequest = { setter ->
                        pickingPaths = txJsonPaths
                        pickingForSetter = setter
                    },
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
                                    customFields =
                                        customAccountFields
                                            .filter { f -> f.name.isNotBlank() }
                                            .associate { f -> f.name.trim() to f.path.trim() },
                                    uniqueIdentifierFields =
                                        customAccountFields
                                            .filter { f -> f.name.isNotBlank() && f.isUniqueId }
                                            .map { f -> f.name.trim() }
                                            .toSet(),
                                ),
                            transactionMappings =
                                ApiTransactionMappings(
                                    amountField = txAmountField.trim(),
                                    timestampField = txTimestampField.trim(),
                                    currencyField = txCurrencyField.trim(),
                                    descriptionField = txDescriptionField.trim(),
                                    merchantNameField = txMerchantNameField.trim().ifBlank { null },
                                    counterpartyNameField = txCounterpartyNameField.trim().ifBlank { null },
                                    counterpartyIdField = txCounterpartyIdField.trim().ifBlank { null },
                                    declineReasonField = txDeclineReasonField.trim().ifBlank { null },
                                    customFields =
                                        customTxFields
                                            .filter { it.name.isNotBlank() }
                                            .associate { it.name.trim() to it.path.trim() },
                                    uniqueIdentifierFields =
                                        customTxFields
                                            .filter { it.name.isNotBlank() && it.isUniqueId }
                                            .map { it.name.trim() }
                                            .toSet(),
                                ),
                            accountNamePrefix = accountNamePrefix.trim(),
                            counterpartyPrefix = counterpartyPrefix.trim(),
                            peopleMappings =
                                com.moneymanager.domain.model.apistrategy.ApiPeopleMappings(
                                    counterpartyObjectField = peopleCounterpartyObjectField.trim(),
                                    beneficiaryAccountTypeField = peopleBeneficiaryAccountTypeField.trim(),
                                    personalBeneficiaryAccountTypeValue = peoplePersonalBeneficiaryAccountTypeValue.trim(),
                                    counterpartyNameField = peopleCounterpartyNameField.trim(),
                                    counterpartyUserIdField = peopleCounterpartyUserIdField.trim(),
                                    counterpartySortCodeField = peopleCounterpartySortCodeField.trim(),
                                    counterpartyAccountNumberField = peopleCounterpartyAccountNumberField.trim(),
                                    counterpartyServiceUserNumberField = peopleCounterpartyServiceUserNumberField.trim(),
                                    fallbackCounterpartyAccountIdSuffix = peopleFallbackCounterpartyAccountIdSuffix.trim(),
                                ),
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

// ── Sub-composables ────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** Small status line indicating whether session data is available for path picking. */
@Composable
private fun SessionDataStatus(
    paths: List<JsonPathEntry>,
    loaded: Boolean,
) {
    val text =
        when {
            !loaded -> "Loading session data…"
            paths.isNotEmpty() -> "${paths.size} paths available from last session — tap ⊞ to pick"
            else -> "No session data yet — type paths manually"
        }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

/**
 * A single standard field mapping row. When session paths are available the ⊞ icon opens
 * the [JsonNodePickerDialog] so the user can click a node instead of typing the path.
 */
@Composable
private fun FieldMappingRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    paths: List<JsonPathEntry>,
    onPickRequest: (setter: (String) -> Unit) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            modifier = Modifier.weight(1f),
            singleLine = true,
        )
        if (paths.isNotEmpty()) {
            IconButton(onClick = { onPickRequest(onValueChange) }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.List,
                    contentDescription = "Pick from session JSON",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Expandable list of user-defined custom field mappings with add and remove controls.
 */
@Composable
private fun CustomFieldsSection(
    fields: List<CustomFieldState>,
    onFieldsChange: (List<CustomFieldState>) -> Unit,
    paths: List<JsonPathEntry>,
    onPickRequest: (setter: (String) -> Unit) -> Unit,
) {
    Text(
        text = "Custom Fields",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    fields.forEachIndexed { index, field ->
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = field.name,
                    onValueChange = { updated ->
                        onFieldsChange(fields.toMutableList().also { it[index] = field.copy(name = updated) })
                    },
                    label = { Text("Field name") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = field.path,
                    onValueChange = { updated ->
                        onFieldsChange(fields.toMutableList().also { it[index] = field.copy(path = updated) })
                    },
                    label = { Text("JSON path") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                if (paths.isNotEmpty()) {
                    IconButton(onClick = {
                        onPickRequest { selected ->
                            onFieldsChange(fields.toMutableList().also { it[index] = field.copy(path = selected) })
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.List,
                            contentDescription = "Pick from session JSON",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                IconButton(onClick = {
                    onFieldsChange(fields.toMutableList().also { it.removeAt(index) })
                }) {
                    Icon(Icons.Default.Close, contentDescription = "Remove field", tint = MaterialTheme.colorScheme.error)
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 4.dp),
            ) {
                Checkbox(
                    checked = field.isUniqueId,
                    onCheckedChange = { checked ->
                        onFieldsChange(fields.toMutableList().also { it[index] = field.copy(isUniqueId = checked) })
                    },
                )
                Text(
                    text = "Use as unique identifier for duplicate detection",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    TextButton(
        onClick = { onFieldsChange(fields + CustomFieldState("", "")) },
        modifier = Modifier.padding(top = 2.dp),
    ) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.width(4.dp))
        Text("Add Custom Field")
    }
}

/** Holds the editable state for a single custom field mapping row. */
private data class CustomFieldState(
    val name: String,
    val path: String,
    val isUniqueId: Boolean = false,
)

/**
 * Dialog showing JSON paths extracted from a real session response.
 * Each row shows the dot-notation path and a sample value from the data.
 * Tapping a row selects that path for the field being edited.
 */
@Composable
private fun JsonNodePickerDialog(
    paths: List<JsonPathEntry>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select JSON field") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(paths) { entry ->
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(entry.path) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                    ) {
                        Text(
                            text = entry.path,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (entry.preview.isNotEmpty()) {
                            Text(
                                text = entry.preview,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

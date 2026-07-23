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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.model.apistrategy.ApiAccountBridge
import com.moneymanager.domain.model.apistrategy.ApiAmountFormat
import com.moneymanager.domain.model.apistrategy.ApiDataEndpoint
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiEndpointKind
import com.moneymanager.domain.model.apistrategy.ApiInternalTransferReconcile
import com.moneymanager.domain.model.apistrategy.ApiSignSource
import com.moneymanager.domain.model.apistrategy.ApiSyntheticAccount
import com.moneymanager.domain.model.apistrategy.ApiTradeMappings
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.apistrategy.InstrumentSplitMode
import com.moneymanager.domain.model.apistrategy.TimestampFormat
import com.moneymanager.domain.model.apistrategy.TransferDirection
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Kinds whose records are interpreted by [ApiTradeMappings]; the rest use [ApiTransactionMappings]. */
private val TRADE_KINDS = setOf(ApiEndpointKind.TRADES, ApiEndpointKind.ORDERS)

/** Kinds that carry a fixed movement direction + external counterparty account. */
internal val DIRECTIONAL_KINDS = setOf(ApiEndpointKind.DEPOSITS, ApiEndpointKind.WITHDRAWALS)

/**
 * Editor for the optional [ApiSyntheticAccount]: an exchange strategy imports into one fixed account
 * holding all assets instead of enumerating an accounts endpoint.
 */
@Composable
internal fun SyntheticAccountEditor(
    account: ApiSyntheticAccount?,
    onChange: (ApiSyntheticAccount?) -> Unit,
    enabled: Boolean,
) {
    ToggleRow(
        label = "Single synthetic account (exchanges)",
        checked = account != null,
        onCheckedChange = { on -> onChange(if (on) ApiSyntheticAccount(name = "", externalId = "") else null) },
        enabled = enabled,
    )
    val a = account ?: return
    TextFieldRow("Account name", a.name, { onChange(a.copy(name = it)) }, enabled, isError = a.name.isBlank())
    TextFieldRow("External id", a.externalId, { onChange(a.copy(externalId = it)) }, enabled, isError = a.externalId.isBlank())
}

/**
 * Editor for the strategy's [ApiDataEndpoint] list — exchanges expose several endpoints (trades,
 * deposits, withdrawals, orders), each mapping to a different kind of imported record.
 */
@Composable
internal fun DataEndpointsEditor(
    endpoints: List<ApiDataEndpoint>,
    onChange: (List<ApiDataEndpoint>) -> Unit,
    enabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        endpoints.forEachIndexed { index, dataEndpoint ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            ) {
                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    EditorCardHeader(
                        title = "Data endpoint ${index + 1}",
                        onRemove = { onChange(endpoints.filterIndexed { i, _ -> i != index }) },
                        enabled = enabled,
                    )
                    DataEndpointEditor(
                        dataEndpoint = dataEndpoint,
                        onChange = { updated -> onChange(endpoints.mapIndexed { i, e -> if (i == index) updated else e }) },
                        enabled = enabled,
                    )
                }
            }
        }
        TextButton(
            onClick = {
                onChange(
                    endpoints +
                        ApiDataEndpoint(
                            endpoint = ApiEndpointConfig(path = "", responseArrayKey = ""),
                            kind = ApiEndpointKind.TRADES,
                        ),
                )
            },
            enabled = enabled,
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Add data endpoint")
        }
    }
}

@Composable
private fun DataEndpointEditor(
    dataEndpoint: ApiDataEndpoint,
    onChange: (ApiDataEndpoint) -> Unit,
    enabled: Boolean,
) {
    EnumDropdown(
        label = "Kind",
        options = ApiEndpointKind.entries,
        selected = dataEndpoint.kind,
        // Persist the direction the UI shows by default (IN) when switching to a directional kind,
        // so a saved deposit/withdrawal endpoint never keeps a null direction the UI rendered as IN.
        // A trade/order endpoint has no transactionMappings, so enrichesTransfers (which needs one) can
        // never be valid there — clear it when switching to a trade kind.
        onSelect = { newKind ->
            val direction = dataEndpoint.fixedDirection ?: TransferDirection.IN.takeIf { newKind in DIRECTIONAL_KINDS }
            val enriches = dataEndpoint.enrichesTransfers && newKind !in TRADE_KINDS
            onChange(dataEndpoint.copy(kind = newKind, fixedDirection = direction, enrichesTransfers = enriches))
        },
        optionLabel = { it.name },
        enabled = enabled,
    )
    EndpointEditor(
        endpoint = dataEndpoint.endpoint,
        onChange = { onChange(dataEndpoint.copy(endpoint = it)) },
        enabled = enabled,
    )

    if (dataEndpoint.kind in TRADE_KINDS) {
        Text("Trade mappings", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TradeMappingsEditor(
            mappings = dataEndpoint.tradeMappings ?: defaultTradeMappings(),
            onChange = { onChange(dataEndpoint.copy(tradeMappings = it, transactionMappings = null)) },
            enabled = enabled,
        )
    } else {
        Text("Transaction mappings", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TransactionMappingsFields(
            mappings = dataEndpoint.transactionMappings ?: ApiTransactionMappings(),
            onChange = { onChange(dataEndpoint.copy(transactionMappings = it, tradeMappings = null)) },
            enabled = enabled,
        )
    }

    // Trade/order endpoints have no transactionMappings, which enrichesTransfers validation requires —
    // hiding the toggle there keeps every reachable state saveable.
    if (dataEndpoint.kind !in TRADE_KINDS) {
        ToggleRow(
            label = "Enrichment only (no money movement — supplies fields for another endpoint's joinKeyField)",
            checked = dataEndpoint.enrichesTransfers,
            onCheckedChange = { onChange(dataEndpoint.copy(enrichesTransfers = it)) },
            enabled = enabled,
        )
    }

    if (dataEndpoint.kind in DIRECTIONAL_KINDS && !dataEndpoint.enrichesTransfers) {
        EnumDropdown(
            label = "Fixed direction",
            options = TransferDirection.entries,
            selected = dataEndpoint.fixedDirection ?: TransferDirection.IN,
            onSelect = { onChange(dataEndpoint.copy(fixedDirection = it)) },
            optionLabel = { it.name },
            enabled = enabled,
        )
        TextFieldRow(
            label = "Counterparty account name (optional)",
            value = dataEndpoint.counterpartyAccountName.orEmpty(),
            onValueChange = { onChange(dataEndpoint.copy(counterpartyAccountName = it.ifBlank { null })) },
            enabled = enabled,
        )
    }
}

/**
 * Structured editor for an [ApiTransactionMappings] on a data endpoint. Mirrors the fields exposed by
 * the main Transactions tab, using plain text fields (data endpoints have no session sample to pick from).
 */
@Composable
internal fun TransactionMappingsFields(
    mappings: ApiTransactionMappings,
    onChange: (ApiTransactionMappings) -> Unit,
    enabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextFieldRow(
            "Amount field",
            mappings.amountField,
            { onChange(mappings.copy(amountField = it)) },
            enabled,
            isError = mappings.amountField.isBlank(),
        )
        TextFieldRow(
            "Timestamp field",
            mappings.timestampField,
            { onChange(mappings.copy(timestampField = it)) },
            enabled,
            isError = mappings.timestampField.isBlank(),
        )
        TextFieldRow(
            "Currency field",
            mappings.currencyField,
            { onChange(mappings.copy(currencyField = it)) },
            enabled,
            isError = mappings.currencyField.isBlank(),
        )
        TextFieldRow("Description field", mappings.descriptionField, {
            onChange(mappings.copy(descriptionField = it))
        }, enabled, isError = mappings.descriptionField.isBlank())
        TextFieldRow(
            "Transaction ID field",
            mappings.idField,
            { onChange(mappings.copy(idField = it)) },
            enabled,
            isError = mappings.idField.isBlank(),
        )
        EnumDropdown(
            label = "Amount format",
            options = ApiAmountFormat.entries,
            selected = mappings.amountFormat,
            onSelect = { onChange(mappings.copy(amountFormat = it)) },
            optionLabel = { it.name },
            enabled = enabled,
        )
        EnumDropdown(
            label = "Timestamp format",
            options = TimestampFormat.entries,
            selected = mappings.timestampFormat,
            onSelect = { onChange(mappings.copy(timestampFormat = it)) },
            optionLabel = { it.name },
            enabled = enabled,
        )
        EnumDropdown(
            label = "Sign source",
            options = ApiSignSource.entries,
            selected = mappings.signSource,
            onSelect = { onChange(mappings.copy(signSource = it)) },
            optionLabel = { it.name },
            enabled = enabled,
        )
        if (mappings.signSource == ApiSignSource.FIELD) {
            TextFieldRow("Sign field", mappings.signField.orEmpty(), { onChange(mappings.copy(signField = it.ifBlank { null })) }, enabled)
            StringSetEditor(
                label = "Credit values (mean incoming/positive)",
                values = mappings.creditValues,
                onChange = { onChange(mappings.copy(creditValues = it)) },
                enabled = enabled,
            )
        }
        TextFieldRow(
            "Fee amount field (optional)",
            mappings.feeAmountField.orEmpty(),
            { onChange(mappings.copy(feeAmountField = it.ifBlank { null })) },
            enabled,
        )
        TextFieldRow("Fee currency field (optional)", mappings.feeCurrencyField.orEmpty(), {
            onChange(mappings.copy(feeCurrencyField = it.ifBlank { null }))
        }, enabled)
        TextFieldRow(
            "Counterparty name field (optional)",
            mappings.counterpartyNameField.orEmpty(),
            { onChange(mappings.copy(counterpartyNameField = it.ifBlank { null })) },
            enabled,
        )
        TextFieldRow(
            "Txid field (optional)",
            mappings.txidField.orEmpty(),
            { onChange(mappings.copy(txidField = it.ifBlank { null })) },
            enabled,
        )
        TextFieldRow(
            "Counterparty address field (optional)",
            mappings.counterpartyAddressField.orEmpty(),
            { onChange(mappings.copy(counterpartyAddressField = it.ifBlank { null })) },
            enabled,
        )
        TextFieldRow(
            "Counterparty network field (optional)",
            mappings.counterpartyNetworkField.orEmpty(),
            { onChange(mappings.copy(counterpartyNetworkField = it.ifBlank { null })) },
            enabled,
        )
        TextFieldRow(
            "Join key field (optional, matches an enrichesTransfers endpoint's id)",
            mappings.joinKeyField.orEmpty(),
            { onChange(mappings.copy(joinKeyField = it.ifBlank { null })) },
            enabled,
        )
        TextFieldRow(
            "Counterparty alias field (optional, e.g. address)",
            mappings.counterpartyAliasField.orEmpty(),
            { onChange(mappings.copy(counterpartyAliasField = it.ifBlank { null })) },
            enabled,
        )
        StringMapEditor(
            label = "Counterparty account aliases (alias value -> owned account name)",
            entries = mappings.counterpartyAccountAliases,
            onChange = { onChange(mappings.copy(counterpartyAccountAliases = it)) },
            keyLabel = "Alias value",
            valueLabel = "Account name",
            enabled = enabled,
        )
    }
}

/** Structured editor for an [ApiTradeMappings] on a TRADES/ORDERS data endpoint. */
@Composable
internal fun TradeMappingsEditor(
    mappings: ApiTradeMappings,
    onChange: (ApiTradeMappings) -> Unit,
    enabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextFieldRow("Instrument field", mappings.instrumentField, {
            onChange(mappings.copy(instrumentField = it))
        }, enabled, isError = mappings.instrumentField.isBlank())
        EnumDropdown(
            label = "Instrument split mode",
            options = InstrumentSplitMode.entries,
            selected = mappings.splitMode,
            onSelect = { onChange(mappings.copy(splitMode = it)) },
            optionLabel = { it.name },
            enabled = enabled,
        )
        when (mappings.splitMode) {
            InstrumentSplitMode.SEPARATOR ->
                TextFieldRow(
                    "Instrument separator",
                    mappings.instrumentSeparator,
                    { onChange(mappings.copy(instrumentSeparator = it)) },
                    enabled,
                )
            InstrumentSplitMode.EXPLICIT_FIELDS -> {
                TextFieldRow(
                    "Base asset field",
                    mappings.baseAssetField.orEmpty(),
                    { onChange(mappings.copy(baseAssetField = it.ifBlank { null })) },
                    enabled,
                )
                TextFieldRow(
                    "Quote asset field",
                    mappings.quoteAssetField.orEmpty(),
                    { onChange(mappings.copy(quoteAssetField = it.ifBlank { null })) },
                    enabled,
                )
            }
            InstrumentSplitMode.QUOTE_SUFFIX ->
                StringSetEditor(
                    label = "Known quote assets (longest match wins)",
                    values = mappings.quoteAssets.toSet(),
                    onChange = { onChange(mappings.copy(quoteAssets = it.toList())) },
                    enabled = enabled,
                )
        }
        TextFieldRow(
            "Side field",
            mappings.sideField,
            { onChange(mappings.copy(sideField = it)) },
            enabled,
            isError = mappings.sideField.isBlank(),
        )
        StringSetEditor(
            label = "Buy values (mean BUY side)",
            values = mappings.buyValues,
            onChange = { onChange(mappings.copy(buyValues = it)) },
            enabled = enabled,
        )
        TextFieldRow("Base quantity field", mappings.baseQuantityField, {
            onChange(mappings.copy(baseQuantityField = it))
        }, enabled, isError = mappings.baseQuantityField.isBlank())
        TextFieldRow(
            "Price field (optional)",
            mappings.priceField.orEmpty(),
            { onChange(mappings.copy(priceField = it.ifBlank { null })) },
            enabled,
        )
        TextFieldRow("Quote quantity field (optional)", mappings.quoteQuantityField.orEmpty(), {
            onChange(mappings.copy(quoteQuantityField = it.ifBlank { null }))
        }, enabled)
        TextFieldRow(
            "Fee field (optional)",
            mappings.feeField.orEmpty(),
            { onChange(mappings.copy(feeField = it.ifBlank { null })) },
            enabled,
        )
        TextFieldRow("Fee currency field (optional)", mappings.feeCurrencyField.orEmpty(), {
            onChange(mappings.copy(feeCurrencyField = it.ifBlank { null }))
        }, enabled)
        TextFieldRow(
            "Timestamp field",
            mappings.timestampField,
            { onChange(mappings.copy(timestampField = it)) },
            enabled,
            isError = mappings.timestampField.isBlank(),
        )
        EnumDropdown(
            label = "Timestamp format",
            options = TimestampFormat.entries,
            selected = mappings.timestampFormat,
            onSelect = { onChange(mappings.copy(timestampFormat = it)) },
            optionLabel = { it.name },
            enabled = enabled,
        )
        TextFieldRow(
            "Trade ID field",
            mappings.idField,
            { onChange(mappings.copy(idField = it)) },
            enabled,
            isError = mappings.idField.isBlank(),
        )
        TextFieldRow(
            "Order ID field (optional)",
            mappings.orderIdField.orEmpty(),
            { onChange(mappings.copy(orderIdField = it.ifBlank { null })) },
            enabled,
        )
        TextFieldRow("Description field (optional)", mappings.descriptionField.orEmpty(), {
            onChange(mappings.copy(descriptionField = it.ifBlank { null }))
        }, enabled)
        TextFieldRow(
            "Order type field (optional)",
            mappings.orderTypeField.orEmpty(),
            { onChange(mappings.copy(orderTypeField = it.ifBlank { null })) },
            enabled,
        )
        TextFieldRow("Order status field (optional)", mappings.orderStatusField.orEmpty(), {
            onChange(mappings.copy(orderStatusField = it.ifBlank { null }))
        }, enabled)
        TextFieldRow("Limit price field (optional)", mappings.limitPriceField.orEmpty(), {
            onChange(mappings.copy(limitPriceField = it.ifBlank { null }))
        }, enabled)
        TextFieldRow("Average price field (optional)", mappings.avgPriceField.orEmpty(), {
            onChange(mappings.copy(avgPriceField = it.ifBlank { null }))
        }, enabled)
        TextFieldRow("Update timestamp field (optional)", mappings.updateTimestampField.orEmpty(), {
            onChange(mappings.copy(updateTimestampField = it.ifBlank { null }))
        }, enabled)
        TextFieldRow("Client order id field (optional)", mappings.clientOidField.orEmpty(), {
            onChange(mappings.copy(clientOidField = it.ifBlank { null }))
        }, enabled)
        TextFieldRow("Time-in-force field (optional)", mappings.timeInForceField.orEmpty(), {
            onChange(mappings.copy(timeInForceField = it.ifBlank { null }))
        }, enabled)
    }
}

/**
 * Editor for the optional [ApiInternalTransferReconcile]: collapses matching half-transfers recorded by
 * this strategy's account and another owned account into one internal transfer.
 */
@Composable
internal fun InternalTransferReconcileEditor(
    config: ApiInternalTransferReconcile?,
    onChange: (ApiInternalTransferReconcile?) -> Unit,
    enabled: Boolean,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    personRepository: PersonReadRepository,
) {
    ToggleRow(
        label = "Enable internal-transfer reconciliation",
        checked = config != null,
        onCheckedChange = { on ->
            onChange(if (on) ApiInternalTransferReconcile(bridges = emptyList(), windowSeconds = 3600) else null)
        },
        enabled = enabled,
    )
    val c = config ?: return

    // account.name is the cross-source key a bridge matches by, so picking from existing accounts (or
    // creating a new one inline) avoids a typo that silently fails to match at import time.
    val accounts by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) { accountRepository.getAllAccounts() }
    val scope = rememberSchemaAwareCoroutineScope()

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Bridged accounts", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        c.bridges.forEachIndexed { index, bridge ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    EditorCardHeader(
                        title = "Bridge ${index + 1}",
                        onRemove = { onChange(c.copy(bridges = c.bridges.filterIndexed { i, _ -> i != index })) },
                        enabled = enabled,
                    )
                    AccountPicker(
                        selectedAccountId = accounts.firstOrNull { it.name == bridge.otherAccountName }?.id,
                        onAccountSelected = { accountId ->
                            fun applyName(name: String) =
                                onChange(
                                    c.copy(
                                        bridges =
                                            c.bridges.mapIndexed { i, b ->
                                                if (i == index) b.copy(otherAccountName = name) else b
                                            },
                                    ),
                                )
                            // Update synchronously from the already-collected snapshot whenever possible, like
                            // every other field on this tab, so the Save button reacts immediately. Only fall
                            // back to a DB lookup for a just-created account this snapshot hasn't re-emitted yet.
                            val cachedName = accounts.firstOrNull { it.id == accountId }?.name
                            if (cachedName != null) {
                                applyName(cachedName)
                            } else {
                                scope.launch {
                                    val selectedName = accountRepository.getAccountById(accountId).first()?.name ?: return@launch
                                    applyName(selectedName)
                                }
                            }
                        },
                        label = "Other account",
                        accountRepository = accountRepository,
                        categoryRepository = categoryRepository,
                        personRepository = personRepository,
                        enabled = enabled,
                        isError = bridge.otherAccountName.isBlank(),
                    )
                }
            }
        }
        TextButton(onClick = { onChange(c.copy(bridges = c.bridges + ApiAccountBridge(otherAccountName = ""))) }, enabled = enabled) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Add bridge")
        }
        LongFieldRow(
            label = "Window (seconds)",
            value = c.windowSeconds,
            onValueChange = { onChange(c.copy(windowSeconds = it)) },
            enabled = enabled,
            isError = c.windowSeconds <= 0,
            supportingText = "Must be a positive number of seconds",
        )
        TextFieldRow(
            label = "Amount tolerance percent",
            value = c.amountTolerancePercent,
            onValueChange = { onChange(c.copy(amountTolerancePercent = it)) },
            enabled = enabled,
            isError = !c.amountTolerancePercent.isNonNegativeDecimal(),
            supportingText = "Decimal string, e.g. \"0.5\" (allowed amount difference as a percentage)",
        )
    }
}

private fun defaultTradeMappings(): ApiTradeMappings =
    ApiTradeMappings(
        instrumentField = "",
        sideField = "",
        baseQuantityField = "",
        timestampField = "",
        idField = "",
    )

private fun ApiTradeMappings.isValidForSave(): Boolean =
    instrumentField.isNotBlank() &&
        sideField.isNotBlank() &&
        baseQuantityField.isNotBlank() &&
        timestampField.isNotBlank() &&
        idField.isNotBlank() &&
        // The base×price / explicit-quote-quantity derivation needs at least one of these.
        (!priceField.isNullOrBlank() || !quoteQuantityField.isNullOrBlank()) &&
        when (splitMode) {
            InstrumentSplitMode.SEPARATOR -> instrumentSeparator.isNotBlank()
            InstrumentSplitMode.EXPLICIT_FIELDS -> !baseAssetField.isNullOrBlank() && !quoteAssetField.isNullOrBlank()
            InstrumentSplitMode.QUOTE_SUFFIX -> quoteAssets.isNotEmpty()
        }

private fun ApiTransactionMappings.isValidForSave(): Boolean =
    amountField.isNotBlank() &&
        timestampField.isNotBlank() &&
        currencyField.isNotBlank() &&
        descriptionField.isNotBlank() &&
        idField.isNotBlank() &&
        (signSource != ApiSignSource.FIELD || !signField.isNullOrBlank())

/** Whether an [ApiEndpointConfig] is complete enough to save, independent of what kind of record it produces. */
private fun ApiEndpointConfig.isValidForSave(): Boolean {
    val pagination = pagination
    return path.isNotBlank() &&
        (successCodeField == null || !successCodeOkValue.isNullOrBlank()) &&
        // A non-positive limitValue would never advance the offset, looping on the same page forever.
        (pagination?.offsetParam == null || pagination.limitValue > 0) &&
        requestCostWeight >= 1
}

/** Whether a data-endpoint list is complete enough to save (used for tab validation). */
internal fun List<ApiDataEndpoint>.isValidForSave(): Boolean =
    all { de ->
        de.endpoint.isValidForSave() &&
            if (de.enrichesTransfers) {
                // An enrichment endpoint moves no money, so only its id field (the join index key) matters.
                de.transactionMappings?.idField?.isNotBlank() ?: false
            } else {
                (de.kind !in DIRECTIONAL_KINDS || de.fixedDirection != null) &&
                    if (de.kind in TRADE_KINDS) {
                        de.tradeMappings?.isValidForSave() ?: false
                    } else {
                        de.transactionMappings?.isValidForSave() ?: false
                    }
            }
    }

/** Whether an internal-transfer-reconcile config is complete enough to save. */
internal fun ApiInternalTransferReconcile.isValidForSave(): Boolean =
    bridges.isNotEmpty() &&
        bridges.all { it.otherAccountName.isNotBlank() } &&
        windowSeconds > 0 &&
        amountTolerancePercent.isNonNegativeDecimal()

/** Whether a synthetic-account config is complete enough to save. */
internal fun ApiSyntheticAccount.isValidForSave(): Boolean = name.isNotBlank() && externalId.isNotBlank()

/**
 * Whether [this] parses as an exact non-negative decimal. Uses [BigDecimal] (parsed from the string)
 * rather than a Double, per the repository's monetary-parsing guideline.
 */
internal fun String.isNonNegativeDecimal(): Boolean = runCatching { BigDecimal(trim()) }.getOrNull()?.let { it >= BigDecimal.ZERO } ?: false

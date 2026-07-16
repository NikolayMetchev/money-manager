package com.moneymanager.ui.screens.apistrategy.editor

import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.apistrategy.ApiAccountMappings
import com.moneymanager.domain.model.apistrategy.ApiAuthType
import com.moneymanager.domain.model.apistrategy.ApiDataEndpoint
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiInternalTransferReconcile
import com.moneymanager.domain.model.apistrategy.ApiPeopleMappings
import com.moneymanager.domain.model.apistrategy.ApiPersonImportConfig
import com.moneymanager.domain.model.apistrategy.ApiRequestSigningConfig
import com.moneymanager.domain.model.apistrategy.ApiSigningConfig
import com.moneymanager.domain.model.apistrategy.ApiSyntheticAccount
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.apistrategy.BuiltInCounterpartyRule
import kotlin.time.Instant

/**
 * Immutable snapshot of the API strategy editor form. Bridges [ApiImportStrategy] ↔ the mutable
 * [ApiStrategyEditorState]. Most config is held as the domain nested types directly; only the
 * `customFields` map and its `uniqueIdentifierFields` companion set are projected onto an editable
 * [CustomFieldState] list (the mapping objects below carry those two fields normalized to empty).
 */
data class ApiStrategyFormState(
    val name: String,
    val baseUrl: String,
    val authType: ApiAuthType,
    val personExternalIdAttribute: String?,
    val accountsEndpoint: ApiEndpointConfig,
    val transactionsEndpoint: ApiEndpointConfig,
    val accountIdentifiersEndpoint: ApiEndpointConfig?,
    val ancestorEndpoints: List<ApiEndpointConfig>,
    val accountMappings: ApiAccountMappings,
    val accountCustomFields: List<CustomFieldState>,
    val transactionMappings: ApiTransactionMappings,
    val txCustomFields: List<CustomFieldState>,
    val peopleMappings: ApiPeopleMappings,
    val builtInCounterpartyRules: List<BuiltInCounterpartyRule>,
    val signing: ApiSigningConfig?,
    val peopleDownload: ApiPersonImportConfig?,
    val requestSigning: ApiRequestSigningConfig?,
    val dataEndpoints: List<ApiDataEndpoint>,
    val syntheticAccount: ApiSyntheticAccount?,
    val internalTransferReconcile: ApiInternalTransferReconcile?,
    val assetAliases: Map<String, String>,
)

/** Projects a mapping's `customFields` map + `uniqueIdentifierFields` set onto editable rows. */
private fun customFieldStates(
    customFields: Map<String, String>,
    uniqueIdentifierFields: Set<String>,
): List<CustomFieldState> = customFields.map { (name, path) -> CustomFieldState(name, path, name in uniqueIdentifierFields) }

private fun List<CustomFieldState>.toCustomFieldMap(): Map<String, String> =
    filter { it.name.isNotBlank() }.associate { it.name.trim() to it.path.trim() }

private fun List<CustomFieldState>.toUniqueIdentifierFields(): Set<String> =
    filter { it.name.isNotBlank() && it.isUniqueId }.map { it.name.trim() }.toSet()

/** Extracts editable form state from a persisted [ApiImportStrategy]. */
fun extractFormStateFromStrategy(strategy: ApiImportStrategy): ApiStrategyFormState =
    ApiStrategyFormState(
        name = strategy.name,
        baseUrl = strategy.baseUrl,
        authType = strategy.authType,
        personExternalIdAttribute = strategy.personExternalIdAttribute,
        accountsEndpoint = strategy.accountsEndpoint,
        transactionsEndpoint = strategy.transactionsEndpoint,
        accountIdentifiersEndpoint = strategy.accountIdentifiersEndpoint,
        ancestorEndpoints = strategy.ancestorEndpoints,
        accountMappings = strategy.accountMappings.copy(customFields = emptyMap(), uniqueIdentifierFields = emptySet()),
        accountCustomFields =
            customFieldStates(strategy.accountMappings.customFields, strategy.accountMappings.uniqueIdentifierFields),
        transactionMappings = strategy.transactionMappings.copy(customFields = emptyMap(), uniqueIdentifierFields = emptySet()),
        txCustomFields =
            customFieldStates(strategy.transactionMappings.customFields, strategy.transactionMappings.uniqueIdentifierFields),
        peopleMappings = strategy.peopleMappings,
        builtInCounterpartyRules = strategy.builtInCounterpartyRules,
        signing = strategy.signing,
        peopleDownload = strategy.peopleDownload,
        requestSigning = strategy.requestSigning,
        dataEndpoints = strategy.dataEndpoints,
        syntheticAccount = strategy.syntheticAccount,
        internalTransferReconcile = strategy.internalTransferReconcile,
        assetAliases = strategy.assetAliases,
    )

/** Reassembles an [ApiImportStrategy] from edited form state. The DB regenerates revisionId/configJson. */
fun buildStrategyFromApiFormState(
    state: ApiStrategyFormState,
    id: ApiImportStrategyId,
    createdAt: Instant,
    updatedAt: Instant,
): ApiImportStrategy =
    ApiImportStrategy(
        id = id,
        name = state.name.trim(),
        baseUrl = state.baseUrl.trim(),
        authType = state.authType,
        accountsEndpoint = state.accountsEndpoint,
        transactionsEndpoint = state.transactionsEndpoint,
        accountMappings =
            state.accountMappings.copy(
                customFields = state.accountCustomFields.toCustomFieldMap(),
                uniqueIdentifierFields = state.accountCustomFields.toUniqueIdentifierFields(),
            ),
        transactionMappings =
            state.transactionMappings.copy(
                customFields = state.txCustomFields.toCustomFieldMap(),
                uniqueIdentifierFields = state.txCustomFields.toUniqueIdentifierFields(),
            ),
        peopleMappings = state.peopleMappings,
        accountIdentifiersEndpoint = state.accountIdentifiersEndpoint,
        ancestorEndpoints = state.ancestorEndpoints,
        builtInCounterpartyRules = state.builtInCounterpartyRules,
        signing = state.signing,
        peopleDownload = state.peopleDownload,
        personExternalIdAttribute = state.personExternalIdAttribute?.trim()?.ifBlank { null },
        requestSigning = state.requestSigning,
        dataEndpoints = state.dataEndpoints,
        syntheticAccount = state.syntheticAccount,
        internalTransferReconcile = state.internalTransferReconcile,
        assetAliases = state.assetAliases,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

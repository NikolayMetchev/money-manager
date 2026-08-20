package com.moneymanager.ui.screens.apistrategy.editor

import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiStrategyConfig
import kotlin.time.Instant

/**
 * Immutable snapshot of the API strategy editor form. Bridges [ApiImportStrategy] ↔ the mutable
 * [ApiStrategyEditorState]. Most config is held as the domain [ApiStrategyConfig] directly; only the
 * `customFields` map and its `uniqueIdentifierFields` companion set are projected onto an editable
 * [CustomFieldState] list (the mapping objects inside [config] carry those two fields normalized to
 * empty).
 */
data class ApiStrategyFormState(
    val name: String,
    val config: ApiStrategyConfig,
    val accountCustomFields: List<CustomFieldState>,
    val txCustomFields: List<CustomFieldState>,
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
fun extractFormStateFromStrategy(strategy: ApiImportStrategy): ApiStrategyFormState {
    val accounts = strategy.config.accountMappings
    val transactions = strategy.config.transactionMappings
    return ApiStrategyFormState(
        name = strategy.name,
        config =
            strategy.config.copy(
                accountMappings = accounts.copy(customFields = emptyMap(), uniqueIdentifierFields = emptySet()),
                transactionMappings = transactions.copy(customFields = emptyMap(), uniqueIdentifierFields = emptySet()),
            ),
        accountCustomFields = customFieldStates(accounts.customFields, accounts.uniqueIdentifierFields),
        txCustomFields = customFieldStates(transactions.customFields, transactions.uniqueIdentifierFields),
    )
}

/** Reassembles an [ApiImportStrategy] from edited form state. The DB regenerates revisionId/configJson. */
fun buildStrategyFromApiFormState(
    state: ApiStrategyFormState,
    id: ApiImportStrategyId,
    createdAt: Instant,
    updatedAt: Instant,
): ApiImportStrategy {
    val config = state.config
    return ApiImportStrategy(
        id = id,
        name = state.name.trim(),
        config =
            config.copy(
                baseUrl = config.baseUrl.trim(),
                accountMappings =
                    config.accountMappings.copy(
                        customFields = state.accountCustomFields.toCustomFieldMap(),
                        uniqueIdentifierFields = state.accountCustomFields.toUniqueIdentifierFields(),
                    ),
                transactionMappings =
                    config.transactionMappings.copy(
                        customFields = state.txCustomFields.toCustomFieldMap(),
                        uniqueIdentifierFields = state.txCustomFields.toUniqueIdentifierFields(),
                    ),
                personExternalIdAttribute = config.personExternalIdAttribute?.trim()?.ifBlank { null },
                tokenPageUrl = config.tokenPageUrl?.trim()?.ifBlank { null },
                connectInstructions = config.connectInstructions.map { it.trim() }.filter { it.isNotEmpty() },
                rateLimitErrorSubstrings = config.rateLimitErrorSubstrings.map { it.trim() }.filter { it.isNotEmpty() },
            ),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

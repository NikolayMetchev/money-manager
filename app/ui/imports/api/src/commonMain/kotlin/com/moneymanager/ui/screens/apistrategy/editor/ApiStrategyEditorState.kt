package com.moneymanager.ui.screens.apistrategy.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.apistrategy.ApiAccountMappings
import com.moneymanager.domain.model.apistrategy.ApiAuthType
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiPersonImportConfig
import com.moneymanager.domain.model.apistrategy.ApiQueryParam
import com.moneymanager.domain.model.apistrategy.ApiSignSource
import com.moneymanager.domain.model.apistrategy.ApiStrategyConfig
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.apistrategy.PredicateOp
import com.moneymanager.domain.model.apistrategy.TransferDirection
import kotlin.time.Instant

/** Tabs of the API strategy editor screen. */
internal enum class EditorTab(
    val title: String,
) {
    GENERAL("General"),
    ENDPOINTS("Endpoints"),
    ACCOUNT_MAPPINGS("Accounts"),
    TRANSACTION_MAPPINGS("Transactions"),
    PEOPLE("People"),
    RULES("Rules"),
    ADVANCED("Advanced"),
}

/** Whether `op` requires a [com.moneymanager.domain.model.apistrategy.RulePredicate.value] operand. */
internal fun PredicateOp.requiresValue(): Boolean =
    when (this) {
        PredicateOp.EQUALS, PredicateOp.EQUALS_IGNORE_CASE, PredicateOp.STARTS_WITH, PredicateOp.ARRAY_ANY_STARTS_WITH -> true
        PredicateOp.NOT_EQUALS, PredicateOp.IN -> true
        PredicateOp.EXISTS, PredicateOp.OBJECT_EMPTY, PredicateOp.OBJECT_NON_EMPTY -> false
    }

private val DEFAULT_ACCOUNTS_ENDPOINT = ApiEndpointConfig(path = "/accounts", responseArrayKey = "accounts")
private val DEFAULT_TRANSACTIONS_ENDPOINT =
    ApiEndpointConfig(
        path = "/transactions",
        responseArrayKey = "transactions",
        queryParams = listOf(ApiQueryParam(name = "account_id", dynamicSource = "account.id")),
    )

/** The config a create-mode editor starts from: the required arguments, everything else at its default. */
private val NEW_CONFIG =
    ApiStrategyConfig(
        baseUrl = "",
        authType = ApiAuthType.BEARER_TOKEN,
        accountsEndpoint = DEFAULT_ACCOUNTS_ENDPOINT,
        transactionsEndpoint = DEFAULT_TRANSACTIONS_ENDPOINT,
        accountMappings = ApiAccountMappings(),
        transactionMappings = ApiTransactionMappings(),
    )

/**
 * Full mutable editing state of the API strategy editor, held across tab switches. Seeded straight
 * from [strategy] when editing, or from defaults when creating.
 *
 * [config] is the domain [ApiStrategyConfig] itself rather than a field-for-field mirror of it, so
 * the tabs edit it with `copy` and a config field that no tab knows about — a newly added one, or
 * `valueEndpoints` — keeps its persisted value instead of silently resetting on the next save.
 * The only genuinely projected state is [name] (trimmed on save) and the two custom-field lists: a
 * mapping's `customFields` map plus its `uniqueIdentifierFields` companion set become one editable
 * row list, so the mappings inside [config] carry both normalized to empty until [buildStrategy].
 */
internal class ApiStrategyEditorState(
    strategy: ApiImportStrategy?,
) {
    var selectedTab by mutableStateOf(EditorTab.GENERAL)
    var isSaving by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)

    var name by mutableStateOf(strategy?.name.orEmpty())

    var config by mutableStateOf(strategy?.config?.withoutCustomFields()?.backfillDataEndpoints() ?: NEW_CONFIG)

    var accountCustomFields by mutableStateOf(strategy?.config?.accountMappings.customFieldStates())
    var txCustomFields by mutableStateOf(strategy?.config?.transactionMappings.customFieldStates())

    /** Replaces [config] with the result of [block] applied to its current value. */
    fun updateConfig(block: ApiStrategyConfig.() -> ApiStrategyConfig) {
        config = config.block()
    }

    val generalHasError: Boolean
        get() =
            name.isBlank() ||
                config.baseUrl.isBlank() ||
                (config.rateLimitMillis?.let { it < 0 } == true) ||
                config.rateLimitBackoffMillis <= 0 ||
                config.maxRateLimitRetries < 0

    val endpointsHasError: Boolean
        get() =
            config.accountsEndpoint.path.isBlank() ||
                config.transactionsEndpoint.path.isBlank() ||
                config.accountIdentifiersEndpoint?.path?.isBlank() == true ||
                config.ancestorEndpoints.any { it.path.isBlank() } ||
                config.syntheticAccount?.let { !it.isValidForSave() } == true ||
                !config.dataEndpoints.isValidForSave()

    val advancedHasError: Boolean
        get() =
            config.requestSigning?.let { !it.isValidForSave() } == true ||
                config.internalTransferReconcile?.let { !it.isValidForSave() } == true

    val accountMappingsHasError: Boolean
        get() = config.accountMappings.idField.isBlank() || config.accountMappings.descriptionField.isBlank()

    val transactionMappingsHasError: Boolean
        get() =
            config.transactionMappings.let { mappings ->
                mappings.amountField.isBlank() ||
                    mappings.timestampField.isBlank() ||
                    mappings.currencyField.isBlank() ||
                    mappings.descriptionField.isBlank() ||
                    mappings.idField.isBlank() ||
                    (mappings.signSource == ApiSignSource.FIELD && mappings.signField.isNullOrBlank())
            }

    val peopleHasError: Boolean
        get() =
            config.peopleDownload?.let { it.endpoint.path.isBlank() || it.firstNameField.isBlank() || !it.ownershipValid() } == true

    val rulesHasError: Boolean
        get() =
            config.builtInCounterpartyRules.any { rule ->
                rule.name.isBlank() ||
                    rule.predicates.any { it.path.isBlank() || (it.op.requiresValue() && it.value.isNullOrBlank()) }
            }

    fun tabHasError(tab: EditorTab): Boolean =
        when (tab) {
            EditorTab.GENERAL -> generalHasError
            EditorTab.ENDPOINTS -> endpointsHasError
            EditorTab.ACCOUNT_MAPPINGS -> accountMappingsHasError
            EditorTab.TRANSACTION_MAPPINGS -> transactionMappingsHasError
            EditorTab.PEOPLE -> peopleHasError
            EditorTab.RULES -> rulesHasError
            EditorTab.ADVANCED -> advancedHasError
        }

    val isValid: Boolean
        get() =
            !generalHasError &&
                !endpointsHasError &&
                !accountMappingsHasError &&
                !transactionMappingsHasError &&
                !peopleHasError &&
                !rulesHasError &&
                !advancedHasError

    /** Reassembles an [ApiImportStrategy] from the edited state. The DB regenerates revisionId/configJson. */
    fun buildStrategy(
        id: ApiImportStrategyId,
        createdAt: Instant,
        updatedAt: Instant,
    ): ApiImportStrategy =
        ApiImportStrategy(
            id = id,
            name = name.trim(),
            config =
                config.copy(
                    baseUrl = config.baseUrl.trim(),
                    accountMappings =
                        config.accountMappings.copy(
                            customFields = accountCustomFields.toCustomFieldMap(),
                            uniqueIdentifierFields = accountCustomFields.toUniqueIdentifierFields(),
                        ),
                    transactionMappings =
                        config.transactionMappings.copy(
                            customFields = txCustomFields.toCustomFieldMap(),
                            uniqueIdentifierFields = txCustomFields.toUniqueIdentifierFields(),
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

/** `ownsAllAccounts` and `accountOwnerAncestorExpr` are mutually exclusive. */
private fun ApiPersonImportConfig.ownershipValid(): Boolean = !(ownsAllAccounts && !accountOwnerAncestorExpr.isNullOrBlank())

/** Clears the two fields the editor projects onto [CustomFieldState] rows; `buildStrategy` puts them back. */
private fun ApiStrategyConfig.withoutCustomFields(): ApiStrategyConfig =
    copy(
        accountMappings = accountMappings.copy(customFields = emptyMap(), uniqueIdentifierFields = emptySet()),
        transactionMappings = transactionMappings.copy(customFields = emptyMap(), uniqueIdentifierFields = emptySet()),
    )

/**
 * A directional (deposit/withdrawal) endpoint with a null fixedDirection displays as "IN" in the
 * Endpoints tab (a rendering fallback), but that fallback is never persisted on its own — so a
 * strategy saved before this field existed, or otherwise missing it, would show a fully-filled-in
 * form yet fail isValidForSave and permanently disable Save. Backfill it here, at load time, so the
 * fix applies without the user ever having to visit the Endpoints tab.
 */
private fun ApiStrategyConfig.backfillDataEndpoints(): ApiStrategyConfig =
    copy(
        dataEndpoints =
            dataEndpoints.map { endpoint ->
                if (endpoint.kind in DIRECTIONAL_KINDS && !endpoint.enrichesTransfers && endpoint.fixedDirection == null) {
                    endpoint.copy(fixedDirection = TransferDirection.IN)
                } else {
                    endpoint
                }
            },
    )

/** Projects a mapping's `customFields` map + `uniqueIdentifierFields` set onto editable rows. */
private fun ApiAccountMappings?.customFieldStates(): List<CustomFieldState> =
    customFieldStates(this?.customFields, this?.uniqueIdentifierFields)

private fun ApiTransactionMappings?.customFieldStates(): List<CustomFieldState> =
    customFieldStates(this?.customFields, this?.uniqueIdentifierFields)

private fun customFieldStates(
    customFields: Map<String, String>?,
    uniqueIdentifierFields: Set<String>?,
): List<CustomFieldState> =
    customFields.orEmpty().map { (name, path) -> CustomFieldState(name, path, name in uniqueIdentifierFields.orEmpty()) }

private fun List<CustomFieldState>.toCustomFieldMap(): Map<String, String> =
    filter { it.name.isNotBlank() }.associate { it.name.trim() to it.path.trim() }

private fun List<CustomFieldState>.toUniqueIdentifierFields(): Set<String> =
    filter { it.name.isNotBlank() && it.isUniqueId }.map { it.name.trim() }.toSet()

/**
 * Remembers an [ApiStrategyEditorState], keyed on [editKey] so it survives recompositions and tab
 * switches but is rebuilt when the edited strategy changes.
 */
@Composable
internal fun rememberApiStrategyEditorState(
    editKey: String,
    strategy: ApiImportStrategy?,
): ApiStrategyEditorState = remember(editKey) { ApiStrategyEditorState(strategy) }

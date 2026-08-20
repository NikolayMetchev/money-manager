package com.moneymanager.ui.screens.apistrategy.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.moneymanager.domain.model.apistrategy.ApiAccountMappings
import com.moneymanager.domain.model.apistrategy.ApiAuthType
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiPeopleMappings
import com.moneymanager.domain.model.apistrategy.ApiPersonImportConfig
import com.moneymanager.domain.model.apistrategy.ApiQueryParam
import com.moneymanager.domain.model.apistrategy.ApiSignSource
import com.moneymanager.domain.model.apistrategy.ApiStrategyConfig
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.apistrategy.PredicateOp
import com.moneymanager.domain.model.apistrategy.TransferDirection

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
        PredicateOp.EXISTS, PredicateOp.OBJECT_EMPTY, PredicateOp.OBJECT_NON_EMPTY -> false
    }

private val DEFAULT_ACCOUNTS_ENDPOINT = ApiEndpointConfig(path = "/accounts", responseArrayKey = "accounts")
private val DEFAULT_TRANSACTIONS_ENDPOINT =
    ApiEndpointConfig(
        path = "/transactions",
        responseArrayKey = "transactions",
        queryParams = listOf(ApiQueryParam(name = "account_id", dynamicSource = "account.id")),
    )

/**
 * Full mutable editing state of the API strategy editor, held across tab switches. Seeded from
 * [initial] (extracted from an existing strategy) when editing, or from defaults when creating.
 */
internal class ApiStrategyEditorState(
    initial: ApiStrategyFormState?,
) {
    /**
     * The config this editor was seeded from, kept so [toFormState] can `copy` the edited fields onto
     * it rather than rebuilding a config from scratch. A field added to [ApiStrategyConfig] later but
     * not wired into the editor then keeps its persisted value instead of silently resetting to its
     * default on the next save. Null when creating a strategy, where there is nothing to preserve.
     */
    private val seededConfig = initial?.config

    var selectedTab by mutableStateOf(EditorTab.GENERAL)
    var isSaving by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)

    var name by mutableStateOf(initial?.name.orEmpty())
    var baseUrl by mutableStateOf(initial?.config?.baseUrl.orEmpty())
    var authType by mutableStateOf(initial?.config?.authType ?: ApiAuthType.BEARER_TOKEN)
    var personExternalIdAttribute by mutableStateOf(initial?.config?.personExternalIdAttribute.orEmpty())
    var tokenPageUrl by mutableStateOf(initial?.config?.tokenPageUrl.orEmpty())
    var connectInstructions by mutableStateOf(initial?.config?.connectInstructions.orEmpty())
    var rateLimitMillis by mutableStateOf(initial?.config?.rateLimitMillis)
    var rateLimitErrorSubstrings by mutableStateOf(initial?.config?.rateLimitErrorSubstrings.orEmpty())
    var rateLimitBackoffMillis by mutableStateOf(initial?.config?.rateLimitBackoffMillis ?: 5_000L)
    var maxRateLimitRetries by mutableStateOf(initial?.config?.maxRateLimitRetries ?: 5)

    var accountsEndpoint by mutableStateOf(initial?.config?.accountsEndpoint ?: DEFAULT_ACCOUNTS_ENDPOINT)
    var transactionsEndpoint by mutableStateOf(initial?.config?.transactionsEndpoint ?: DEFAULT_TRANSACTIONS_ENDPOINT)
    var accountIdentifiersEndpoint by mutableStateOf(initial?.config?.accountIdentifiersEndpoint)
    var ancestorEndpoints by mutableStateOf(initial?.config?.ancestorEndpoints.orEmpty())

    var accountMappings by mutableStateOf(initial?.config?.accountMappings ?: ApiAccountMappings())
    var accountCustomFields by mutableStateOf(initial?.accountCustomFields.orEmpty())
    var transactionMappings by mutableStateOf(initial?.config?.transactionMappings ?: ApiTransactionMappings())
    var txCustomFields by mutableStateOf(initial?.txCustomFields.orEmpty())
    var peopleMappings by mutableStateOf(initial?.config?.peopleMappings ?: ApiPeopleMappings())
    var builtInCounterpartyRules by mutableStateOf(initial?.config?.builtInCounterpartyRules.orEmpty())
    var signing by mutableStateOf(initial?.config?.signing)
    var peopleDownload by mutableStateOf(initial?.config?.peopleDownload)

    // Config-driven exchange fields (crypto.com/Binance/Kraken). Held as domain types directly and
    // edited on the Endpoints tab (synthetic account, data endpoints) and Advanced tab (request
    // signing, internal-transfer reconciliation).
    var requestSigning by mutableStateOf(initial?.config?.requestSigning)

    // A directional (deposit/withdrawal) endpoint with a null fixedDirection displays as "IN" in the
    // Endpoints tab (a rendering fallback), but that fallback is never persisted on its own — so a
    // strategy saved before this field existed, or otherwise missing it, would show a fully-filled-in
    // form yet fail isValidForSave and permanently disable Save. Backfill it here, at load time, so the
    // fix applies without the user ever having to visit the Endpoints tab.
    var dataEndpoints by
        mutableStateOf(
            initial?.config?.dataEndpoints.orEmpty().map { endpoint ->
                if (endpoint.kind in DIRECTIONAL_KINDS && !endpoint.enrichesTransfers && endpoint.fixedDirection == null) {
                    endpoint.copy(fixedDirection = TransferDirection.IN)
                } else {
                    endpoint
                }
            },
        )
    var syntheticAccount by mutableStateOf(initial?.config?.syntheticAccount)
    var internalTransferReconcile by mutableStateOf(initial?.config?.internalTransferReconcile)
    var assetAliases by mutableStateOf(initial?.config?.assetAliases.orEmpty())
    var assetSuffixesToStrip by mutableStateOf(initial?.config?.assetSuffixesToStrip.orEmpty())
    var minorUnitDivisorOverrides by mutableStateOf(initial?.config?.minorUnitDivisorOverrides.orEmpty())

    val generalHasError: Boolean
        get() =
            name.isBlank() ||
                baseUrl.isBlank() ||
                (rateLimitMillis?.let { it < 0 } == true) ||
                rateLimitBackoffMillis <= 0 ||
                maxRateLimitRetries < 0

    val endpointsHasError: Boolean
        get() =
            accountsEndpoint.path.isBlank() ||
                transactionsEndpoint.path.isBlank() ||
                accountIdentifiersEndpoint?.path?.isBlank() == true ||
                ancestorEndpoints.any { it.path.isBlank() } ||
                syntheticAccount?.let { !it.isValidForSave() } == true ||
                !dataEndpoints.isValidForSave()

    val advancedHasError: Boolean
        get() =
            requestSigning?.let { !it.isValidForSave() } == true ||
                internalTransferReconcile?.let { !it.isValidForSave() } == true

    val accountMappingsHasError: Boolean
        get() = accountMappings.idField.isBlank() || accountMappings.descriptionField.isBlank()

    val transactionMappingsHasError: Boolean
        get() =
            transactionMappings.amountField.isBlank() ||
                transactionMappings.timestampField.isBlank() ||
                transactionMappings.currencyField.isBlank() ||
                transactionMappings.descriptionField.isBlank() ||
                transactionMappings.idField.isBlank() ||
                (transactionMappings.signSource == ApiSignSource.FIELD && transactionMappings.signField.isNullOrBlank())

    val peopleHasError: Boolean
        get() = peopleDownload?.let { it.endpoint.path.isBlank() || it.firstNameField.isBlank() || !it.ownershipValid() } == true

    val rulesHasError: Boolean
        get() =
            builtInCounterpartyRules.any { rule ->
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

    fun toFormState(): ApiStrategyFormState =
        ApiStrategyFormState(
            name = name,
            config =
                (seededConfig ?: newConfig()).copy(
                    baseUrl = baseUrl,
                    authType = authType,
                    accountsEndpoint = accountsEndpoint,
                    transactionsEndpoint = transactionsEndpoint,
                    accountMappings = accountMappings,
                    transactionMappings = transactionMappings,
                    peopleMappings = peopleMappings,
                    accountIdentifiersEndpoint = accountIdentifiersEndpoint,
                    ancestorEndpoints = ancestorEndpoints,
                    builtInCounterpartyRules = builtInCounterpartyRules,
                    signing = signing,
                    peopleDownload = peopleDownload,
                    personExternalIdAttribute = personExternalIdAttribute,
                    requestSigning = requestSigning,
                    dataEndpoints = dataEndpoints,
                    syntheticAccount = syntheticAccount,
                    internalTransferReconcile = internalTransferReconcile,
                    assetAliases = assetAliases,
                    tokenPageUrl = tokenPageUrl,
                    connectInstructions = connectInstructions,
                    rateLimitMillis = rateLimitMillis,
                    rateLimitErrorSubstrings = rateLimitErrorSubstrings,
                    rateLimitBackoffMillis = rateLimitBackoffMillis,
                    maxRateLimitRetries = maxRateLimitRetries,
                    assetSuffixesToStrip = assetSuffixesToStrip,
                    minorUnitDivisorOverrides = minorUnitDivisorOverrides,
                ),
            accountCustomFields = accountCustomFields,
            txCustomFields = txCustomFields,
        )

    /** The [ApiStrategyConfig] required arguments, for the create case where there is no seed to copy. */
    private fun newConfig() =
        ApiStrategyConfig(
            baseUrl = baseUrl,
            authType = authType,
            accountsEndpoint = accountsEndpoint,
            transactionsEndpoint = transactionsEndpoint,
            accountMappings = accountMappings,
            transactionMappings = transactionMappings,
        )
}

/** `ownsAllAccounts` and `accountOwnerAncestorExpr` are mutually exclusive. */
private fun ApiPersonImportConfig.ownershipValid(): Boolean = !(ownsAllAccounts && !accountOwnerAncestorExpr.isNullOrBlank())

/**
 * Remembers an [ApiStrategyEditorState], keyed on [editKey] so it survives recompositions and tab
 * switches but is rebuilt when the edited strategy changes.
 */
@Composable
internal fun rememberApiStrategyEditorState(
    editKey: String,
    initial: ApiStrategyFormState?,
): ApiStrategyEditorState = remember(editKey) { ApiStrategyEditorState(initial) }

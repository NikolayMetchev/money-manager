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
    var selectedTab by mutableStateOf(EditorTab.GENERAL)
    var isSaving by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)

    var name by mutableStateOf(initial?.name.orEmpty())
    var baseUrl by mutableStateOf(initial?.baseUrl.orEmpty())
    var authType by mutableStateOf(initial?.authType ?: ApiAuthType.BEARER_TOKEN)
    var personExternalIdAttribute by mutableStateOf(initial?.personExternalIdAttribute.orEmpty())
    var tokenPageUrl by mutableStateOf(initial?.tokenPageUrl.orEmpty())
    var connectInstructions by mutableStateOf(initial?.connectInstructions.orEmpty())
    var rateLimitMillis by mutableStateOf(initial?.rateLimitMillis)
    var rateLimitErrorSubstrings by mutableStateOf(initial?.rateLimitErrorSubstrings.orEmpty())
    var rateLimitBackoffMillis by mutableStateOf(initial?.rateLimitBackoffMillis ?: 5_000L)
    var maxRateLimitRetries by mutableStateOf(initial?.maxRateLimitRetries ?: 5)

    var accountsEndpoint by mutableStateOf(initial?.accountsEndpoint ?: DEFAULT_ACCOUNTS_ENDPOINT)
    var transactionsEndpoint by mutableStateOf(initial?.transactionsEndpoint ?: DEFAULT_TRANSACTIONS_ENDPOINT)
    var accountIdentifiersEndpoint by mutableStateOf(initial?.accountIdentifiersEndpoint)
    var ancestorEndpoints by mutableStateOf(initial?.ancestorEndpoints.orEmpty())

    var accountMappings by mutableStateOf(initial?.accountMappings ?: ApiAccountMappings())
    var accountCustomFields by mutableStateOf(initial?.accountCustomFields.orEmpty())
    var transactionMappings by mutableStateOf(initial?.transactionMappings ?: ApiTransactionMappings())
    var txCustomFields by mutableStateOf(initial?.txCustomFields.orEmpty())
    var peopleMappings by mutableStateOf(initial?.peopleMappings ?: ApiPeopleMappings())
    var builtInCounterpartyRules by mutableStateOf(initial?.builtInCounterpartyRules.orEmpty())
    var signing by mutableStateOf(initial?.signing)
    var peopleDownload by mutableStateOf(initial?.peopleDownload)

    // Config-driven exchange fields (crypto.com/Binance/Kraken). Held as domain types directly and
    // edited on the Endpoints tab (synthetic account, data endpoints) and Advanced tab (request
    // signing, internal-transfer reconciliation).
    var requestSigning by mutableStateOf(initial?.requestSigning)

    // A directional (deposit/withdrawal) endpoint with a null fixedDirection displays as "IN" in the
    // Endpoints tab (a rendering fallback), but that fallback is never persisted on its own — so a
    // strategy saved before this field existed, or otherwise missing it, would show a fully-filled-in
    // form yet fail isValidForSave and permanently disable Save. Backfill it here, at load time, so the
    // fix applies without the user ever having to visit the Endpoints tab.
    var dataEndpoints by
        mutableStateOf(
            initial?.dataEndpoints.orEmpty().map { endpoint ->
                if (endpoint.kind in DIRECTIONAL_KINDS && !endpoint.enrichesTransfers && endpoint.fixedDirection == null) {
                    endpoint.copy(fixedDirection = TransferDirection.IN)
                } else {
                    endpoint
                }
            },
        )
    var syntheticAccount by mutableStateOf(initial?.syntheticAccount)
    var internalTransferReconcile by mutableStateOf(initial?.internalTransferReconcile)
    var assetAliases by mutableStateOf(initial?.assetAliases.orEmpty())
    var assetSuffixesToStrip by mutableStateOf(initial?.assetSuffixesToStrip.orEmpty())
    var minorUnitDivisorOverrides by mutableStateOf(initial?.minorUnitDivisorOverrides.orEmpty())

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
            baseUrl = baseUrl,
            authType = authType,
            personExternalIdAttribute = personExternalIdAttribute,
            tokenPageUrl = tokenPageUrl,
            connectInstructions = connectInstructions,
            rateLimitMillis = rateLimitMillis,
            rateLimitErrorSubstrings = rateLimitErrorSubstrings,
            rateLimitBackoffMillis = rateLimitBackoffMillis,
            maxRateLimitRetries = maxRateLimitRetries,
            accountsEndpoint = accountsEndpoint,
            transactionsEndpoint = transactionsEndpoint,
            accountIdentifiersEndpoint = accountIdentifiersEndpoint,
            ancestorEndpoints = ancestorEndpoints,
            accountMappings = accountMappings,
            accountCustomFields = accountCustomFields,
            transactionMappings = transactionMappings,
            txCustomFields = txCustomFields,
            peopleMappings = peopleMappings,
            builtInCounterpartyRules = builtInCounterpartyRules,
            signing = signing,
            peopleDownload = peopleDownload,
            requestSigning = requestSigning,
            dataEndpoints = dataEndpoints,
            syntheticAccount = syntheticAccount,
            internalTransferReconcile = internalTransferReconcile,
            assetAliases = assetAliases,
            assetSuffixesToStrip = assetSuffixesToStrip,
            minorUnitDivisorOverrides = minorUnitDivisorOverrides,
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

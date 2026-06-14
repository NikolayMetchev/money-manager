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

/** Whether [op] requires a [com.moneymanager.domain.model.apistrategy.RulePredicate.value] operand. */
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
    var accountNamePrefix by mutableStateOf(initial?.accountNamePrefix.orEmpty())
    var counterpartyPrefix by mutableStateOf(initial?.counterpartyPrefix.orEmpty())
    var personExternalIdAttribute by mutableStateOf(initial?.personExternalIdAttribute.orEmpty())

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

    val generalHasError: Boolean
        get() = name.isBlank() || baseUrl.isBlank()

    val endpointsHasError: Boolean
        get() =
            accountsEndpoint.path.isBlank() ||
                transactionsEndpoint.path.isBlank() ||
                accountIdentifiersEndpoint?.path?.isBlank() == true ||
                ancestorEndpoints.any { it.path.isBlank() }

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

    val advancedHasError: Boolean
        get() = false

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
            accountNamePrefix = accountNamePrefix,
            counterpartyPrefix = counterpartyPrefix,
            personExternalIdAttribute = personExternalIdAttribute,
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

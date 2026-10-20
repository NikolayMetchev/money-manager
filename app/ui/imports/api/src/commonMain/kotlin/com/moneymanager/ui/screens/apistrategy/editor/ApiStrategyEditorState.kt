package com.moneymanager.ui.screens.apistrategy.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.apistrategy.ApiAccountMappings
import com.moneymanager.domain.model.apistrategy.ApiAccountsSource
import com.moneymanager.domain.model.apistrategy.ApiDataEndpoint
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiEndpointKind
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiPersonImportConfig
import com.moneymanager.domain.model.apistrategy.ApiQueryParam
import com.moneymanager.domain.model.apistrategy.ApiStrategyConfig
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.ui.components.rules.isComplete
import kotlin.time.Instant

/** Tabs of the API strategy editor screen. */
internal enum class EditorTab(
    val title: String,
) {
    GENERAL("General"),
    ENDPOINTS("Endpoints"),
    ACCOUNT_MAPPINGS("Accounts"),
    TRANSACTION_MAPPINGS("Transactions"),
    RULES("Rules"),
    PEOPLE("People"),
    ADVANCED("Advanced"),
}

internal val DEFAULT_ACCOUNTS_ENDPOINT = ApiEndpointConfig(path = "/accounts", responseArrayKey = "accounts")
internal val DEFAULT_TRANSACTIONS_ENDPOINT =
    ApiEndpointConfig(
        path = "/transactions",
        responseArrayKey = "transactions",
        queryParams = listOf(ApiQueryParam(name = "account_id", dynamicSource = "account.id")),
    )

/** The config a create-mode editor starts from: the required arguments, everything else at its default. */
private val NEW_CONFIG =
    ApiStrategyConfig(
        baseUrl = "",
        accounts = ApiAccountsSource.Downloaded(endpoint = DEFAULT_ACCOUNTS_ENDPOINT),
        dataEndpoints =
            listOf(
                ApiDataEndpoint(
                    endpoint = DEFAULT_TRANSACTIONS_ENDPOINT,
                    kind = ApiEndpointKind.BANK_TRANSACTIONS,
                    transactionMappings = ApiTransactionMappings(),
                ),
            ),
    )

/** The account mappings the Accounts tab edits: a downloaded-accounts source's, else the defaults. */
internal val ApiStrategyConfig.editedAccountMappings: ApiAccountMappings
    get() = (accounts as? ApiAccountsSource.Downloaded)?.mappings ?: ApiAccountMappings()

/** This config with [mappings] as its downloaded-accounts source's mappings (unchanged for a single account). */
internal fun ApiStrategyConfig.withAccountMappings(mappings: ApiAccountMappings): ApiStrategyConfig =
    (accounts as? ApiAccountsSource.Downloaded)?.let { copy(accounts = it.copy(mappings = mappings)) } ?: this

/** The transaction mappings the Transactions tab edits: the bank feed's, else the defaults. */
internal val ApiStrategyConfig.editedTransactionMappings: ApiTransactionMappings
    get() = bankTransactions?.transactionMappings ?: ApiTransactionMappings()

/**
 * Full mutable editing state of the API strategy editor, held across tab switches. Seeded straight
 * from [strategy] when editing, or from defaults when creating.
 *
 * [config] is the domain [ApiStrategyConfig] itself rather than a field-for-field mirror of it, so
 * the tabs edit it with `copy` and a config field that no tab knows about — a newly added one, or
 * `valueEndpoints` — keeps its persisted value instead of silently resetting on the next save.
 * The only projected state is [name], trimmed on save.
 */
internal class ApiStrategyEditorState(
    strategy: ApiImportStrategy?,
) {
    var selectedTab by mutableStateOf(EditorTab.GENERAL)
    var isSaving by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)

    var name by mutableStateOf(strategy?.name.orEmpty())

    var config by mutableStateOf(strategy?.config ?: NEW_CONFIG)

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
            when (val accounts = config.accounts) {
                is ApiAccountsSource.Single -> {
                    !accounts.isValidForSave()
                }

                is ApiAccountsSource.Downloaded -> {
                    accounts.endpoint.path.isBlank() ||
                        accounts.identifiersEndpoint?.path?.isBlank() == true ||
                        accounts.ancestorEndpoints.any { it.path.isBlank() } ||
                        config.bankTransactions
                            ?.endpoint
                            ?.path
                            ?.isBlank() != false
                }
            } ||
                !config.dataEndpoints.filter { it.kind != ApiEndpointKind.BANK_TRANSACTIONS }.isValidForSave()

    val advancedHasError: Boolean
        get() =
            config.requestSigning?.let { !it.isValidForSave() } == true ||
                config.tokenExchange?.let { !it.isValidForSave() } == true ||
                config.internalTransferReconcile?.let { !it.isValidForSave() } == true

    val accountMappingsHasError: Boolean
        get() =
            config.editedAccountMappings.let { mappings ->
                mappings.idField.isBlank() ||
                    mappings.descriptionField.isBlank()
            }

    val transactionMappingsHasError: Boolean
        get() =
            config.bankTransactions != null &&
                config.editedTransactionMappings.let { mappings ->
                    mappings.amountField.isBlank() ||
                        mappings.timestampField.isBlank() ||
                        mappings.currencyField.isBlank() ||
                        mappings.descriptionField.isBlank() ||
                        mappings.idField.isBlank()
                }

    /**
     * Whether a rule list on the Rules tab has a condition or action missing its inputs. An incomplete rule is
     * not inert: a path-less "is blank" skip holds for every item and would drop them all.
     */
    val rulesHasError: Boolean
        get() = !config.ruleLists().all { it.rules.isComplete() }

    val peopleHasError: Boolean
        get() =
            config.peopleDownload?.let { it.endpoint.path.isBlank() || it.firstNameField.isBlank() || !it.ownershipValid() } == true

    fun tabHasError(tab: EditorTab): Boolean =
        when (tab) {
            EditorTab.GENERAL -> generalHasError
            EditorTab.ENDPOINTS -> endpointsHasError
            EditorTab.ACCOUNT_MAPPINGS -> accountMappingsHasError
            EditorTab.TRANSACTION_MAPPINGS -> transactionMappingsHasError
            EditorTab.RULES -> rulesHasError
            EditorTab.PEOPLE -> peopleHasError
            EditorTab.ADVANCED -> advancedHasError
        }

    val isValid: Boolean
        get() =
            !generalHasError &&
                !endpointsHasError &&
                !accountMappingsHasError &&
                !transactionMappingsHasError &&
                !rulesHasError &&
                !peopleHasError &&
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

/**
 * Remembers an [ApiStrategyEditorState], keyed on [editKey] so it survives recompositions and tab
 * switches but is rebuilt when the edited strategy changes.
 */
@Composable
internal fun rememberApiStrategyEditorState(
    editKey: String,
    strategy: ApiImportStrategy?,
): ApiStrategyEditorState = remember(editKey) { ApiStrategyEditorState(strategy) }

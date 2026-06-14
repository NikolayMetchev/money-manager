@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens.apistrategy.editor

import com.moneymanager.domain.model.apistrategy.ApiAccountMappings
import com.moneymanager.domain.model.apistrategy.ApiAmountFormat
import com.moneymanager.domain.model.apistrategy.ApiAuthType
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId
import com.moneymanager.domain.model.apistrategy.ApiPaginationConfig
import com.moneymanager.domain.model.apistrategy.ApiPeopleMappings
import com.moneymanager.domain.model.apistrategy.ApiPersonImportConfig
import com.moneymanager.domain.model.apistrategy.ApiQueryParam
import com.moneymanager.domain.model.apistrategy.ApiSignSource
import com.moneymanager.domain.model.apistrategy.ApiSigningConfig
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.apistrategy.BuiltInCounterpartyRule
import com.moneymanager.domain.model.apistrategy.PaginationMode
import com.moneymanager.domain.model.apistrategy.PredicateOp
import com.moneymanager.domain.model.apistrategy.RulePredicate
import com.moneymanager.domain.model.apistrategy.RuleSign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ApiStrategyEditorStateTest {
    private val now = Instant.fromEpochMilliseconds(1_000)

    /** A strategy exercising every nested config type, including all the previously-unexposed fields. */
    private fun fullStrategy(): ApiImportStrategy =
        ApiImportStrategy(
            id = ApiImportStrategyId(Uuid.random()),
            name = "Full",
            baseUrl = "https://api.example.com",
            authType = ApiAuthType.BEARER_TOKEN,
            accountsEndpoint = ApiEndpointConfig(path = "/accounts", responseArrayKey = "accounts"),
            transactionsEndpoint =
                ApiEndpointConfig(
                    path = "/transactions",
                    responseArrayKey = "transactions",
                    queryParams = listOf(ApiQueryParam(name = "account_id", dynamicSource = "account.id")),
                    pagination =
                        ApiPaginationConfig(
                            mode = PaginationMode.DATE_WINDOW,
                            windowDays = 90,
                            lookbackDays = 720,
                            extraParams = listOf(ApiQueryParam(name = "currency", value = "GBP")),
                        ),
                ),
            accountMappings =
                ApiAccountMappings(
                    sortCodeField = "sortCode",
                    accountNumberField = "accountNumber",
                    currencyField = "currency",
                    customFields = mapOf("kind" to "type"),
                    uniqueIdentifierFields = setOf("kind"),
                ),
            transactionMappings =
                ApiTransactionMappings(
                    amountFormat = ApiAmountFormat.DECIMAL_MAJOR_UNITS,
                    signSource = ApiSignSource.FIELD,
                    signField = "direction",
                    creditValues = setOf("CREDIT"),
                    declineStatusField = "status",
                    declinedStatusValues = setOf("DECLINED"),
                    feeAmountField = "fee",
                    customFields = mapOf("note" to "reference"),
                    uniqueIdentifierFields = setOf("note"),
                ),
            accountNamePrefix = "Ex: ",
            counterpartyPrefix = "Ex CP: ",
            peopleMappings =
                ApiPeopleMappings(
                    personalBeneficiaryAccountTypeValues = setOf("PAYEE", "SENDER"),
                    preferBankIdentity = true,
                ),
            accountIdentifiersEndpoint =
                ApiEndpointConfig(path = "/accounts/{account.id}/identifiers", responseArrayKey = ""),
            ancestorEndpoints = listOf(ApiEndpointConfig(path = "/profiles", responseArrayKey = "")),
            builtInCounterpartyRules =
                listOf(
                    BuiltInCounterpartyRule(
                        name = "ATM",
                        onlyWhenSign = RuleSign.NEGATIVE,
                        predicates =
                            listOf(
                                RulePredicate(path = "metadata.mcc", op = PredicateOp.EQUALS, value = "6011"),
                                RulePredicate(path = "scheme", op = PredicateOp.EXISTS),
                            ),
                    ),
                ),
            signing = ApiSigningConfig(triggerStatus = 401, statementCountries = setOf("GB", "US")),
            peopleDownload =
                ApiPersonImportConfig(
                    endpoint = ApiEndpointConfig(path = "/profiles", responseArrayKey = ""),
                    firstNameField = "details.firstName",
                    lastNameField = "details.lastName",
                    ownsAllAccounts = true,
                ),
            personExternalIdAttribute = "example-external-id",
            createdAt = now,
            updatedAt = now,
        )

    @Test
    fun `round-trips every nested config field through extract then state then build`() {
        val strategy = fullStrategy()

        val extracted = extractFormStateFromStrategy(strategy)
        val state = ApiStrategyEditorState(extracted)
        assertEquals(extracted, state.toFormState())

        val rebuilt =
            buildStrategyFromApiFormState(
                state = state.toFormState(),
                id = strategy.id,
                createdAt = strategy.createdAt,
                updatedAt = strategy.updatedAt,
            )
        // revisionId/configJson are DB-owned and left at defaults by the editor; everything else matches.
        assertEquals(strategy.copy(revisionId = rebuilt.revisionId, configJson = rebuilt.configJson), rebuilt)
    }

    @Test
    fun `create-mode state is valid with defaults plus name and base url`() {
        val state = ApiStrategyEditorState(initial = null)
        assertFalse(state.isValid)
        state.name = "My API"
        state.baseUrl = "https://api.example.com"
        assertTrue(state.isValid)
    }

    @Test
    fun `sign field is required when sign source is FIELD`() {
        val state = ApiStrategyEditorState(initial = null)
        state.name = "My API"
        state.baseUrl = "https://api.example.com"
        state.transactionMappings = state.transactionMappings.copy(signSource = ApiSignSource.FIELD, signField = null)
        assertTrue(state.transactionMappingsHasError)
        state.transactionMappings = state.transactionMappings.copy(signField = "direction")
        assertFalse(state.transactionMappingsHasError)
    }

    @Test
    fun `people download forbids owns-all-accounts together with ancestor expression`() {
        val state = ApiStrategyEditorState(initial = null)
        state.name = "My API"
        state.baseUrl = "https://api.example.com"
        state.peopleDownload =
            ApiPersonImportConfig(
                endpoint = ApiEndpointConfig(path = "/profiles", responseArrayKey = ""),
                firstNameField = "firstName",
                ownsAllAccounts = true,
                accountOwnerAncestorExpr = "ancestor[0].id",
            )
        assertTrue(state.peopleHasError)
        state.peopleDownload = state.peopleDownload?.copy(accountOwnerAncestorExpr = null)
        assertFalse(state.peopleHasError)
    }
}

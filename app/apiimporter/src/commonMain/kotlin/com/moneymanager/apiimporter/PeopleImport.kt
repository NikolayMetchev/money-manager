package com.moneymanager.apiimporter

import co.touchlab.kermit.Logger
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiPersonImportConfig
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.ApiSessionReadRepository
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.getOrCreateAttributeType
import kotlinx.serialization.json.JsonObject

private val logger = Logger.withTag("ApiPeopleImport")

/**
 * The two ownership-linking strategies are mutually exclusive: [ApiPersonImportConfig.ownsAllAccounts]
 * links a single global holder to every account, while [ApiPersonImportConfig.accountOwnerAncestorExpr]
 * derives ownership from the resource hierarchy. Allowing both would silently prefer ownsAllAccounts and
 * persist wrong links, so every code path that acts on the config validates it first.
 */
internal fun validatePeopleOwnershipConfig(config: ApiPersonImportConfig) {
    require(!(config.ownsAllAccounts && config.accountOwnerAncestorExpr != null)) {
        "Invalid peopleDownload config: ownsAllAccounts and accountOwnerAncestorExpr are mutually exclusive."
    }
}

/**
 * Imports people from a people-download session: creates a `Person` for each profile holder and, when
 * [accountsSessionId] is provided, links each person as an owner of the accounts fetched under their
 * profile (via [ApiPersonImportConfig.accountOwnerAncestorExpr]).
 */
suspend fun importApiSessionPeople(
    apiSessionRepository: ApiSessionReadRepository,
    accountAttributeRepository: AccountAttributeReadRepository,
    importEngine: ImportEngine,
    sessionId: ApiSessionId,
    strategy: ApiImportStrategy,
    accountsSessionId: ApiSessionId? = null,
): ApiPeopleImportResult {
    val config = strategy.config.peopleDownload ?: return ApiPeopleImportResult(personCount = 0)
    validatePeopleOwnershipConfig(config)
    val externalIdAttributeTypeId = strategy.config.personExternalIdAttribute?.let { importEngine.getOrCreateAttributeType(it) }
    val requestsById = apiSessionRepository.getRequestsBySession(sessionId).associateBy { it.id }
    val peopleResponses =
        apiSessionRepository.getResponsesBySession(sessionId).filter { response ->
            val path = requestsById[response.requestId]?.encodedPath() ?: return@filter false
            extractPathVariables(config.endpoint.path, path) != null
        }
    // The own accounts these people own already exist in the DB; resolve their ids by external id (a
    // read, not a write) so they can be referenced via AccountRef.Existing.
    val ownedAccountsByProfile =
        buildProfileAccountMap(apiSessionRepository, accountAttributeRepository, accountsSessionId, strategy, config)
    // Flat providers with a single global account holder (no ancestor hierarchy) link that holder to
    // every account imported in the session.
    val allSessionAccountIds =
        if (config.ownsAllAccounts) {
            loadSessionAccountIds(apiSessionRepository, accountAttributeRepository, accountsSessionId, strategy)
        } else {
            emptyList()
        }
    // Ownership links can only be created against accounts that already exist. When people are
    // imported before their accounts (no accounts session, or accounts not yet imported), the
    // profile→account map is empty and people are created without ownerships; flag that so the
    // empty-ownership outcome isn't mistaken for a successful link.
    val noLinkableAccounts =
        if (config.ownsAllAccounts) allSessionAccountIds.isEmpty() else ownedAccountsByProfile.isEmpty()
    if (config.accountOwnerAncestorExpr != null || config.ownsAllAccounts) {
        if (noLinkableAccounts) {
            logger.w { "Importing people with no imported accounts to link; ownerships will not be created. Import accounts first." }
        }
    }

    val peopleResolver = BatchPeopleResolver()
    for (response in peopleResponses) {
        val requestId = requestsById[response.requestId]?.id
        responseItemsArray(response.json, config.endpoint.responseArrayKey)
            ?.forEachIndexed { index, element ->
                val obj = element as? JsonObject ?: return@forEachIndexed
                val owner = obj.toPersonOwner(config, index) ?: return@forEachIndexed
                val ownerSource = Source.Api(sessionId, requestId, owner.jsonPath)
                val ownedAccounts =
                    if (config.ownsAllAccounts) allSessionAccountIds else ownedAccountsByProfile[owner.userId].orEmpty()
                if (ownedAccounts.isEmpty()) {
                    peopleResolver.linkPersonOnly(owner, externalIdAttributeTypeId, ownerSource)
                } else {
                    for (accountId in ownedAccounts) {
                        peopleResolver.linkOwner(owner, AccountRef.Existing(accountId), externalIdAttributeTypeId, ownerSource)
                    }
                }
            }
    }

    val people = peopleResolver.intents()
    if (people.isEmpty()) return ApiPeopleImportResult(personCount = 0)
    val batch =
        ImportBatch(
            transfers = emptyList(),
            dedupePolicy = DedupePolicy.None,
            peopleToCreate = people,
            ownerships = peopleResolver.ownershipIntents(),
        )
    val result = importEngine.import(batch)
    return ApiPeopleImportResult(personCount = result.peopleCreated)
}

/** Builds a map of profile external id → the [AccountId]s fetched under that profile. */
internal suspend fun buildProfileAccountMap(
    apiSessionRepository: ApiSessionReadRepository,
    accountAttributeRepository: AccountAttributeReadRepository,
    accountsSessionId: ApiSessionId?,
    strategy: ApiImportStrategy,
    config: ApiPersonImportConfig,
): Map<String, List<AccountId>> {
    val ancestorExpr = config.accountOwnerAncestorExpr ?: return emptyMap()
    if (accountsSessionId == null) return emptyMap()
    val accountIdByExternalId = loadAccountExternalIdIndex(accountAttributeRepository)
    val requestsById = apiSessionRepository.getRequestsBySession(accountsSessionId).associateBy { it.id }
    val result = mutableMapOf<String, MutableList<AccountId>>()
    for (response in apiSessionRepository.getResponsesBySession(accountsSessionId)) {
        val profileId =
            requestsById[response.requestId]
                ?.takeIf { it.isAccountsRequest(strategy) }
                ?.ancestorVars(strategy)
                ?.get(ancestorExpr)
        if (profileId != null) {
            for (account in parseAccounts(response.json, strategy)) {
                accountIdByExternalId[account.externalId]?.let { result.getOrPut(profileId) { mutableListOf() }.add(it) }
            }
        }
    }
    return result
}

/** Returns every [AccountId] imported under [accountsSessionId] (used for [ApiPersonImportConfig.ownsAllAccounts]). */
internal suspend fun loadSessionAccountIds(
    apiSessionRepository: ApiSessionReadRepository,
    accountAttributeRepository: AccountAttributeReadRepository,
    accountsSessionId: ApiSessionId?,
    strategy: ApiImportStrategy,
): List<AccountId> {
    if (accountsSessionId == null) return emptyList()
    val accountIdByExternalId = loadAccountExternalIdIndex(accountAttributeRepository)
    val requestsById = apiSessionRepository.getRequestsBySession(accountsSessionId).associateBy { it.id }
    val result = mutableListOf<AccountId>()
    for (response in apiSessionRepository.getResponsesBySession(accountsSessionId)) {
        val isAccountsResponse = requestsById[response.requestId]?.isAccountsRequest(strategy) == true
        if (isAccountsResponse) {
            for (account in parseAccounts(response.json, strategy)) {
                accountIdByExternalId[account.externalId]?.let { result.add(it) }
            }
        }
    }
    return result.distinct()
}

/** Maps a profile object to an owner; returns null when no usable name can be derived. */
internal fun JsonObject.toPersonOwner(
    config: ApiPersonImportConfig,
    index: Int,
): ApiImportAccountOwner? {
    val externalId = resolveJsonPath(config.externalIdField)?.takeIf { it.isNotBlank() }
    val first =
        config.preferredNameField?.let { resolveJsonPath(it) }?.takeIf { it.isNotBlank() }
            ?: resolveJsonPath(config.firstNameField)?.takeIf { it.isNotBlank() }
    val last = config.lastNameField?.let { resolveJsonPath(it) }?.takeIf { it.isNotBlank() }
    val fallback = config.fallbackNameField?.let { resolveJsonPath(it) }?.takeIf { it.isNotBlank() }
    val name = listOfNotNull(first, last).joinToString(" ").ifBlank { fallback ?: return null }
    return ApiImportAccountOwner(
        userId = externalId.orEmpty(),
        preferredName = name,
        sortCode = null,
        accountNumber = null,
        jsonPath = arrayItemJsonPath(config.endpoint.responseArrayKey, index),
    )
}

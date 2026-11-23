package com.moneymanager.apiimporter

import co.touchlab.kermit.Logger
import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiRequest
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiResponse
import com.moneymanager.domain.model.ApiResponseId
import com.moneymanager.domain.model.ApiResponseTransactionInsert
import com.moneymanager.domain.model.ApiResponseTransactionState
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.apistrategy.ApiAmountFormat
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiPaginationConfig
import com.moneymanager.domain.model.apistrategy.ApiPeopleMappings
import com.moneymanager.domain.model.apistrategy.ApiPersonImportConfig
import com.moneymanager.domain.model.apistrategy.ApiQueryParam
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.apistrategy.TimestampFormat
import com.moneymanager.domain.model.codesByName
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.observation.ImportRecordKey
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.model.resolveAssetCode
import com.moneymanager.domain.model.rules.AccountNaming
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.FeeRule
import com.moneymanager.domain.model.rules.NamedAccount
import com.moneymanager.domain.model.rules.Record
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleAttribute
import com.moneymanager.domain.model.rules.RuleEngine
import com.moneymanager.domain.model.rules.RuleEvaluator
import com.moneymanager.domain.model.rules.RuleOutcome
import com.moneymanager.domain.model.rules.StrategyRule
import com.moneymanager.domain.model.rules.ValueExpr
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.ApiSessionReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.importengineapi.AccountMatchKey
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.ApiSessionMutation
import com.moneymanager.importengineapi.AttributeSlot
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.ExistingApiIdExtractor
import com.moneymanager.importengineapi.ExistingUniqueKeyExtractor
import com.moneymanager.importengineapi.ImportAccountIntent
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportConversion
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportFee
import com.moneymanager.importengineapi.ImportOwnershipIntent
import com.moneymanager.importengineapi.ImportPassThrough
import com.moneymanager.importengineapi.ImportPersonIntent
import com.moneymanager.importengineapi.ImportReportedBalance
import com.moneymanager.importengineapi.ImportResult
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.importengineapi.LocalPersonKey
import com.moneymanager.importengineapi.PassThroughDetector
import com.moneymanager.importengineapi.PersonMatchKey
import com.moneymanager.importengineapi.bankKeysFrom
import com.moneymanager.importengineapi.dedupePolicy
import com.moneymanager.importengineapi.getOrCreateAttributeType
import com.moneymanager.importengineapi.normalizeNameKey
import com.moneymanager.importengineapi.personalCounterpartyKey
import com.moneymanager.importengineapi.recordApiDownloadCoverage
import com.moneymanager.importengineapi.transactionIdAttributeName
import com.moneymanager.importengineapi.uniqueIdentifierAttributeNames
import com.moneymanager.rest.ApiClient
import com.moneymanager.rest.ApiHttpResponse
import com.moneymanager.rest.ScaParams
import com.moneymanager.tasks.Progress
import io.ktor.http.URLBuilder
import io.ktor.http.encodedPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.absoluteValue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** This item's counterparty identity, its user id dropped when the rules call that id throwaway. */
internal fun ApiTransactionPageItem.counterpartyIdentity(
    counterpartyIdField: String?,
    peopleMappings: ApiPeopleMappings,
): String? = rawJson?.resolveCounterpartyIdentity(counterpartyIdField, peopleMappings, ignoreUserId = outcome.ignoreCounterpartyId)

internal fun JsonObject.resolveCounterpartyIdentity(
    counterpartyIdField: String?,
    peopleMappings: ApiPeopleMappings,
    ignoreUserId: Boolean,
): String? {
    // The bank sub-entity (sort code + account number) is the most reliable account identity for
    // providers like Starling, where a single real account can otherwise be split across several
    // counterparty ids. When configured, it wins over the explicit id; the id (and then the name)
    // remain the fallback for counterparties without bank details.
    if (peopleMappings.preferBankIdentity) {
        bankIdentity(peopleMappings)?.let { return it }
    }

    if (counterpartyIdField == null) return null

    resolveJsonPath(counterpartyIdField)?.takeIf { it.isNotBlank() }?.let { return it }

    // counterpartyIdField (e.g. Monzo's "counterparty.id") is often absent while the counterparty's own
    // account id is always present — check it before falling through to the user-id fallback below,
    // which is not stable across a self-transfer (Monzo reports the account holder's own user id there).
    resolveJsonObjectPath(peopleMappings.counterpartyObjectField)
        ?.stringOrNull(peopleMappings.counterpartyAccountIdField)
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }

    bankIdentity(peopleMappings)?.let { return it }

    val counterparty = resolveJsonObjectPath(peopleMappings.counterpartyObjectField) ?: return null
    val serviceUserNumber = counterparty.stringOrNull(peopleMappings.counterpartyServiceUserNumberField)?.takeIf { it.isNotBlank() }
    if (serviceUserNumber != null) {
        return "service_user:$serviceUserNumber"
    }

    val userId = counterparty.stringOrNull(peopleMappings.counterpartyUserIdField)?.takeIf { it.isNotBlank() }
    // An ephemeral user id (e.g. Monzo's per-transaction "anonuser_…") is not a stable identity, so it
    // must not key the counterparty account — leaving it to be matched by name keeps the same real
    // person from fragmenting into one account per transaction.
    if (userId != null && !ignoreUserId) {
        return "user:$userId"
    }

    return null
}

/**
 * The counterparty's bank identity ("bank:<sortCode>:<accountNumber>") when both the sort code and
 * account number are present, else null. Resolved relative to [ApiPeopleMappings.counterpartyObjectField]
 * (blank means the transaction item itself, for providers with flat counterparty fields).
 */
internal fun JsonObject.bankIdentity(peopleMappings: ApiPeopleMappings): String? {
    val counterparty = resolveJsonObjectPath(peopleMappings.counterpartyObjectField) ?: return null
    val sortCode = counterparty.stringOrNull(peopleMappings.counterpartySortCodeField)?.takeIf { it.isNotBlank() } ?: return null
    val accountNumber =
        counterparty.stringOrNull(peopleMappings.counterpartyAccountNumberField)?.takeIf { it.isNotBlank() } ?: return null
    return "bank:$sortCode:$accountNumber"
}

/**
 * Derives the bank dedupe key ("sortCode|accountNumber") from a counterparty id of the form
 * "bank:<sortCode>:<accountNumber>" (produced by [bankIdentity] / [resolveCounterpartyIdentity]), or
 * null for any other id. This lets a counterparty resolved purely by bank identity merge with an
 * account carrying the same sort code + account number even when it is not flagged a personal
 * beneficiary — so every representation of one real bank account collapses to a single account.
 */
internal fun bankDedupeKeyFromCounterpartyId(counterpartyId: String?): String? {
    if (counterpartyId == null || !counterpartyId.startsWith("bank:")) return null
    val (sortCode, accountNumber) = counterpartyId.removePrefix("bank:").split(":").takeIf { it.size == 2 } ?: return null
    return if (sortCode.isNotBlank() && accountNumber.isNotBlank()) {
        personalCounterpartyKey(sortCode, accountNumber)
    } else {
        null
    }
}

/**
 * Splits a bank dedupe key ("sortCode|accountNumber", as produced by [PersonalCounterpartyIdentity.dedupeKey]
 * and [bankDedupeKeyFromCounterpartyId]) back into its sort code + account number, or null if it isn't one.
 * Lets a bank-identified counterparty persist its identity as sort-code/account-number attributes even when
 * it wasn't flagged a personal beneficiary, so a later import of the other provider can bank-match it.
 */
internal fun bankIdentityFromDedupeKey(dedupeKey: String?): Pair<String, String>? {
    val parts = dedupeKey?.split("|")?.takeIf { it.size == 2 } ?: return null
    val (sortCode, accountNumber) = parts
    return if (sortCode.isNotBlank() && accountNumber.isNotBlank()) sortCode to accountNumber else null
}

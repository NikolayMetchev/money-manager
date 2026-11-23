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
import com.moneymanager.importengineapi.PassThroughMatch
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

private val logger = Logger.withTag("ApiTransactionPreparation")

/**
 * Concurrently parses + prepares every transaction in the session into [PreparedApiTransaction]s (with
 * [AccountRef.Local] account references, allocated through the [BatchAccountResolver]). No DB writes —
 * accounts are only allocated as intents; the engine creates them later. Returns the prepared transfers
 * in deterministic (page, item) order plus the per-item failures recorded as errors.
 */
internal suspend fun prepareTransactionTransfers(setup: ImportSetup): PreparedTransfers {
    val pageContexts =
        setup.transactionResponses.mapIndexedNotNull { index, response ->
            val request = setup.requestsById[response.requestId] ?: return@mapIndexedNotNull null
            val account = setup.accountsById[request.resolveAccountExternalId(setup.strategy)] ?: return@mapIndexedNotNull null
            ApiImportPageContext(
                index = index,
                response = response.toApiHttpResponse(),
                requestId = request.id,
                ownAccountKey = resolveOwnAccountKey(setup, account),
            )
        }

    val pageItems =
        pageContexts.map { context ->
            context to parseTransactionsWithPath(context.response.body, setup.strategy)
        }
    val preparations =
        coroutineScope {
            pageItems
                .flatMap { (context, transactions) ->
                    transactions.mapIndexed { itemIndex, item ->
                        async {
                            prepareTransactionItem(
                                context = context,
                                itemIndex = itemIndex,
                                item = item,
                                setup = setup,
                            )
                        }
                    }
                }.awaitAll()
        }

    val failedRecords = mutableListOf<ResponseTransactionImportRecord>()
    val preparedTransactions = mutableListOf<PreparedApiTransaction>()
    preparations.forEach { preparation ->
        when (preparation) {
            is ApiTransactionPreparation.Prepared -> preparedTransactions += preparation.transaction
            is ApiTransactionPreparation.Failed -> failedRecords += preparation.record
        }
    }

    // Order deterministically so engine outcomes and source recording align.
    val orderedPrepared =
        preparedTransactions.sortedWith(compareBy<PreparedApiTransaction> { it.pageIndex }.thenBy { it.itemIndex })
    return PreparedTransfers(orderedPrepared = orderedPrepared, failedRecords = failedRecords)
}

internal suspend fun prepareTransactionItem(
    context: ApiImportPageContext,
    itemIndex: Int,
    item: ApiTransactionPageItem,
    setup: ImportSetup,
): ApiTransactionPreparation {
    val responseId = ApiResponseId(context.response.responseId)
    val currency =
        setup.currencyCache.getCurrency(item.currencyCode)
            ?: return ApiTransactionPreparation.Failed(
                record =
                    item.errorRecord(
                        pageIndex = context.index,
                        itemIndex = itemIndex,
                        responseId = responseId,
                        message = "Currency not found: ${item.currencyCode}",
                    ),
            )

    return try {
        val prepared =
            prepareValidTransactionItem(
                context = context,
                itemIndex = itemIndex,
                item = item,
                currency = currency,
                responseId = responseId,
                setup = setup,
            )
        ApiTransactionPreparation.Prepared(prepared)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (expected: Exception) {
        logger.e(expected) { "Error importing API transaction: ${expected.message}" }
        ApiTransactionPreparation.Failed(
            record =
                item.errorRecord(
                    pageIndex = context.index,
                    itemIndex = itemIndex,
                    responseId = responseId,
                    message = expected.message ?: "Unknown import error",
                ),
        )
    }
}

internal suspend fun prepareValidTransactionItem(
    context: ApiImportPageContext,
    itemIndex: Int,
    item: ApiTransactionPageItem,
    currency: Currency,
    responseId: ApiResponseId,
    setup: ImportSetup,
): PreparedApiTransaction {
    val ownAccountRef = AccountRef.Local(context.ownAccountKey)
    val counterpartyApiSource =
        AccountApiSource(
            sessionId = setup.sessionId,
            requestId = context.requestId,
            jsonPath = item.counterpartyJsonPath(setup.strategy.config.peopleMappings),
        )
    val data = item.toTransferData(currency, setup.strategy.config.minorUnitDecimalPlaces)
    // A pass-through (conduit) charge such as Curve, detected from the description (e.g. "CRV*…"). An
    // outgoing spend becomes the funding leg own -> conduit and the engine adds the spend leg conduit ->
    // merchant; an incoming refund/cancellation ("Refund: CRV*…") runs both legs the other way. Detection
    // + the conduit/merchant names come from user-editable config; routing here also avoids creating a
    // "CRV*…" junk counterparty account. Declined items (no real movement) and zero-value items (handled
    // as "Void") are never expanded into a spend leg.
    val passThroughMatch =
        if (!item.isZeroAmount && item.declineReason.isNullOrBlank()) {
            setup.passThroughDetector?.detect(data.description)
        } else {
            null
        }

    val counterpartyKey =
        if (passThroughMatch != null) null else resolveCounterpartyKey(item, ownAccountRef, counterpartyApiSource, setup)
    val counterpartyRef = counterpartyKey?.let { AccountRef.Local(it) }
    val transactionApiId =
        item.rawJson
            ?.resolveJsonPath(
                setup.strategy.config
                    .bankFeedMappings()
                    .idField,
            )?.takeIf { it.isNotBlank() }
    val uniqueKey =
        if (setup.uniqueAttributeNames.isNotEmpty() && item.rawJson != null) {
            setup.uniqueAttributeNames.associateWith { typeName ->
                item.outcome.attributes
                    .firstOrNull { it.typeName == typeName }
                    ?.value
                    .orEmpty()
            }
        } else {
            null
        }

    // A fee is its own movement out of the own account into a consolidated per-strategy fee account,
    // linked to the main transfer via a `fee` relationship. Skipped for declined items (no real movement).
    val fee =
        if (item.hasFee && item.declineReason.isNullOrBlank()) {
            buildImportFee(item, ownAccountRef, currency, counterpartyApiSource, setup)
        } else {
            null
        }

    // When the provider's amount is GROSS (already includes the fee, e.g. Monzo's ATM withdrawal where
    // amount = withdrawal_amount + fee_amount), carve the fee out of the main transfer so main + fee sum
    // back to the original amount instead of double-charging the fee.
    val amount =
        if (fee != null &&
            item.feeRule?.includedInAmount == true &&
            fee.amount.asset.id == data.money.asset.id
        ) {
            Money((data.money.amount - fee.amount.amount).coerceAtLeast(BigInteger.ZERO), data.money.asset)
        } else {
            data.money
        }

    // A purchase the own account settled in another currency (a GBP card paying USD) moves the foreign
    // amount; what the account settled becomes its conversion. An unknown foreign currency keeps the
    // settled amount rather than inventing one.
    val foreignMoney = resolveForeignMoney(item, currency, setup)
    val conversion = foreignMoney?.let { ImportConversion(settled = amount, incoming = data.isIncoming) }
    val reportedBalance = resolveReportedBalance(item, currency, setup)
    val movedAmount = foreignMoney ?: amount

    val passThrough =
        passThroughMatch?.let { match ->
            routeThroughConduit(match, data, movedAmount, counterpartyApiSource, setup)
        }

    return PreparedApiTransaction(
        pageIndex = context.index,
        itemIndex = itemIndex,
        responseId = responseId,
        requestId = context.requestId,
        item = item,
        ownAccount = ownAccountRef,
        // Pass-through: funding leg own -> conduit (or conduit -> own for an incoming
        // refund/cancellation). Otherwise the normal own/counterparty pairing.
        source =
            when {
                passThrough != null -> if (data.isIncoming) passThrough.conduit else ownAccountRef
                data.isIncoming -> counterpartyRef!!
                else -> ownAccountRef
            },
        target =
            when {
                passThrough != null -> if (data.isIncoming) ownAccountRef else passThrough.conduit
                data.isIncoming -> ownAccountRef
                else -> counterpartyRef!!
            },
        timestamp = item.created,
        description = data.description,
        amount = movedAmount,
        attributes =
            buildApiTransferAttributes(
                item = item,
                attributeTypeCache = setup.attributeTypeCache,
            ),
        transactionApiId = transactionApiId,
        uniqueKey = uniqueKey,
        fee = fee,
        passThrough = passThrough?.passThrough,
        conversion = conversion,
        reportedBalance = reportedBalance,
    )
}

/** Conduit routing for a pass-through API transaction: the funding-leg conduit target + the spend leg. */
internal data class ConduitRouting(
    val conduit: AccountRef,
    val passThrough: ImportPassThrough,
)

/**
 * Builds the [ImportFee] for a transaction that carries a fee: resolves the fee currency (falling back
 * to the transaction currency) and pays it from the own account into that account's fee account. Returns null when
 * the fee currency is unknown or the fee is zero.
 */
internal suspend fun buildImportFee(
    item: ApiTransactionPageItem,
    ownAccountRef: AccountRef,
    transactionCurrency: Currency,
    apiSource: AccountApiSource,
    setup: ImportSetup,
): ImportFee? {
    // Only fall back to the transaction currency when no fee currency was configured. If one WAS
    // provided but can't be resolved, skip the fee rather than persisting it under the wrong currency
    // (which would also let an included-in-amount carve-out subtract a mismatched-currency amount).
    val feeCurrency =
        when (val code = item.feeCurrencyCode?.takeIf { it.isNotBlank() }) {
            null -> transactionCurrency
            else -> setup.currencyCache.getCurrency(code) ?: return null
        }
    val feeMoney =
        when {
            item.feeAmountMinorUnits != null -> {
                minorUnitsToMoney(item.feeAmountMinorUnits.absoluteValue, feeCurrency, setup.strategy.config.minorUnitDecimalPlaces)
            }

            item.feeAmountDecimalMajor != null -> {
                Money.fromDisplayValue(item.feeAmountDecimalMajor, feeCurrency)
            }

            else -> {
                return null
            }
        }
    if (feeMoney.isZero()) return null
    val feeAccountKey = setup.accountResolver.resolveFeeAccount(ownAccountRef, source = apiSource.toSource())
    // Point the fee's audit trail at the JSON object that holds the fee (e.g. `atm_fees_detailed`)
    // rather than the whole transaction or the bare amount leaf, by extending the transaction path with
    // the fee field's parent (its last `.`-segment dropped). A flat fee field (no parent) falls back to
    // the transaction node.
    val feeNodePath =
        item.feeRule
            ?.amount
            ?.primaryPath
            ?.substringBeforeLast('.', missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() }
    val feeJsonPath =
        if (feeNodePath != null) "${item.jsonPath.value}.$feeNodePath" else item.jsonPath.value
    return ImportFee(
        source = ownAccountRef,
        target = AccountRef.Local(feeAccountKey),
        amount = feeMoney,
        description = item.feeDescription?.ifBlank { null } ?: "Fee",
        relationshipTypeId = RelationshipTypeId(WellKnownIds.FEE_RELATIONSHIP_TYPE_ID),
        rowKey = ImportRowKey.ApiJsonPath(apiSource.requestId, feeJsonPath),
    )
}

internal suspend fun buildApiTransferAttributes(
    item: ApiTransactionPageItem,
    attributeTypeCache: AttributeTypeCache,
): List<NewAttribute> =
    buildList {
        if (!item.declineReason.isNullOrBlank()) {
            add(NewAttribute(typeId = AttributeTypeId(-1), value = "declined: ${item.declineReason}"))
        }
        for (attribute in item.outcome.attributes) {
            add(NewAttribute(typeId = attributeTypeCache.getOrCreate(attribute.typeName), value = attribute.value))
        }
    }

internal fun PreparedApiTransaction.responseRecord(state: ApiResponseTransactionState): ResponseTransactionImportRecord =
    ResponseTransactionImportRecord(
        pageIndex = pageIndex,
        itemIndex = itemIndex,
        responseId = responseId,
        jsonPath = item.jsonPath,
        state = state,
        errorMessage = null,
    )

internal fun ApiTransactionPageItem.errorRecord(
    pageIndex: Int,
    itemIndex: Int,
    responseId: ApiResponseId,
    message: String,
): ResponseTransactionImportRecord =
    ResponseTransactionImportRecord(
        pageIndex = pageIndex,
        itemIndex = itemIndex,
        responseId = responseId,
        jsonPath = jsonPath,
        state = ApiResponseTransactionState.ERROR,
        errorMessage = message,
    )

private suspend fun resolveCounterpartyKey(
    item: ApiTransactionPageItem,
    ownAccountRef: AccountRef,
    counterpartyApiSource: AccountApiSource,
    setup: ImportSetup,
): LocalAccountKey =
    when {
        item.isZeroAmount -> {
            setup.accountResolver.resolveNamedAccount("Void", source = counterpartyApiSource.toSource())
        }

        item.feeAccountCounterparty -> {
            setup.accountResolver.resolveFeeAccount(ownAccountRef, source = counterpartyApiSource.toSource())
        }

        else -> {
            val peopleMappings = setup.strategy.config.peopleMappings
            val builtInCounterpartyType = item.builtInCounterparty
            val counterpartyId = item.counterpartyIdentity(setup.counterpartyIdField, peopleMappings)
            val personalCounterpartyIdentity = item.personalCounterpartyIdentity(peopleMappings)
            setup.accountResolver.resolveCounterpartyAccount(
                counterpartyId = counterpartyId,
                dedupeKey = personalCounterpartyIdentity?.dedupeKey ?: bankDedupeKeyFromCounterpartyId(counterpartyId),
                builtInType = builtInCounterpartyType,
                name = builtInCounterpartyType ?: item.counterpartyName(),
                personalIdentity = personalCounterpartyIdentity,
                personalCounterparty = item.isPersonCounterparty,
                source = counterpartyApiSource.toSource(),
            )
        }
    }

/**
 * A purchase the own account settled in another currency (a GBP card paying USD) moves the foreign
 * amount; what the account settled becomes its conversion. An unknown foreign currency keeps the
 * settled amount rather than inventing one.
 */
private suspend fun resolveForeignMoney(
    item: ApiTransactionPageItem,
    currency: Currency,
    setup: ImportSetup,
): Money? {
    if (item.isZeroAmount) return null
    val foreignCurrency =
        item.foreignCurrencyCode
            ?.let { setup.currencyCache.getCurrency(it) }
            ?.takeIf { it.id != currency.id }
            ?: return null
    return item.foreignAmount?.toMoney(foreignCurrency, setup, magnitudeOnly = true)
}

private suspend fun resolveReportedBalance(
    item: ApiTransactionPageItem,
    currency: Currency,
    setup: ImportSetup,
): Money? {
    val parsed = item.balance ?: return null
    val balanceCurrency =
        item.balanceCurrencyCode?.let { code ->
            setup.currencyCache.getCurrency(code) ?: error("Balance currency not found: $code")
        } ?: currency
    return parsed.toMoney(balanceCurrency, setup, magnitudeOnly = false)
}

private fun ParsedAmount.toMoney(
    currency: Currency,
    setup: ImportSetup,
    magnitudeOnly: Boolean,
): Money? =
    when {
        minorUnits != null -> {
            val units = if (magnitudeOnly) minorUnits.absoluteValue else minorUnits
            minorUnitsToMoney(units, currency, setup.strategy.config.minorUnitDecimalPlaces)
        }

        decimalMajor != null -> {
            Money.fromDisplayValue(decimalMajor, currency)
        }

        else -> {
            null
        }
    }

private suspend fun routeThroughConduit(
    match: PassThroughMatch,
    data: TransferData,
    movedAmount: Money,
    counterpartyApiSource: AccountApiSource,
    setup: ImportSetup,
): ConduitRouting {
    val source = counterpartyApiSource.toSource()
    val conduitRefs =
        match.accounts.map { account ->
            AccountRef.Local(setup.accountResolver.resolveNamedAccount(account.conduitAccountName, source))
        }
    // A mapping on the stripped merchant or on the whole description routes it onto an existing account.
    val merchantRef =
        setup.merchantMappings.find(match.merchantName, data.description)?.let { AccountRef.Existing(it) }
            ?: AccountRef.Local(setup.accountResolver.resolveNamedAccount(match.merchantName, source))
    return ConduitRouting(
        conduit = conduitRefs.first(),
        passThrough =
            ImportPassThrough(
                conduits = conduitRefs,
                merchantTarget = merchantRef,
                amount = movedAmount,
                spendDescriptions = match.hops.map { it.merchantText },
                relationshipTypeId = RelationshipTypeId(match.accounts.first().relationshipTypeId),
                incoming = data.isIncoming,
            ),
    )
}

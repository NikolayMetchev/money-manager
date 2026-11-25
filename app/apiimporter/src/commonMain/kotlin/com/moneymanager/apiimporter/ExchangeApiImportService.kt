package com.moneymanager.apiimporter

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoRegistry
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.apistrategy.ApiAccountsSource
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.TransferDirection
import com.moneymanager.domain.model.codesByName
import com.moneymanager.domain.model.observation.ImportRecordKey
import com.moneymanager.domain.model.resolveAssetCode
import com.moneymanager.domain.model.rules.EXCLUDED_ATTRIBUTE_TYPE_NAME
import com.moneymanager.domain.model.rules.RuleAttribute
import com.moneymanager.domain.model.rules.ownedAttributeValues
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.ApiSessionReadRepository
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.assetAliasMap
import com.moneymanager.importengineapi.AccountMatchKey
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.EXCHANGE_TXN_ID_ATTR
import com.moneymanager.importengineapi.ExistingApiIdExtractor
import com.moneymanager.importengineapi.ImportAccountIntent
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportOrderIntent
import com.moneymanager.importengineapi.ImportReportedBalance
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTradeIntent
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.importengineapi.LocalOrderKey
import com.moneymanager.importengineapi.LocalTradeKey
import com.moneymanager.importengineapi.ObservationOwnerKey
import com.moneymanager.importengineapi.ObservationSlot
import com.moneymanager.importengineapi.createCrypto
import com.moneymanager.importengineapi.dedupePolicy
import com.moneymanager.importengineapi.getOrCreateAttributeType
import com.moneymanager.importengineapi.internalTransferBridges
import com.moneymanager.importengineapi.tradeDedupePolicy
import com.moneymanager.tasks.PhasedProgress
import com.moneymanager.tasks.Progress
import kotlinx.coroutines.flow.first
import kotlin.time.Clock
import kotlin.time.Instant

/*
 * Import for signed exchange strategies — any ApiImportStrategy with a
 * single-account source and dataEndpoints. Entirely config-driven, so there is no per-provider code here. The download
 * lives in `ExchangeDownload`, response parsing in `ExchangeItemParsing`, ledger reconciliation in `ExchangeLedgerReconciliation`.
 * The bank-shaped path (`TransactionsImport`, `TransactionPreparation`) is separate; the two are dispatched by strategy shape.
 */

/** The external-id attribute type name used to match the single exchange account across re-imports. */
private const val EXCHANGE_ACCOUNT_EXTERNAL_ID_ATTR = "exchange-account-external-id"

/** External-id attribute keying a blockchain-wallet counterparty account by its address. */
private const val WALLET_ADDRESS_ATTR = "blockchain-wallet-address"

/** Cross-source unique-id attribute holding a transfer's on-chain transaction id. */
private const val BLOCKCHAIN_TXID_ATTR = "blockchain-txid"

// Slices of the import's progress bar (see PhasedProgress): parsing the stored responses, then
// building the batch, then the engine write — which dominates, so it gets the largest share.
internal const val PARSE_BASE = 0f
internal const val PARSE_SPAN = 0.35f
private const val BUILD_BASE = 0.35f
private const val WRITE_BASE = 0.5f
private const val WRITE_SPAN = 0.5f

/** Emit a parse update every N responses rather than per response — parsing one is cheap. */
internal const val PARSE_PROGRESS_EVERY_RESPONSES = 20

/**
 * Engine write-batch size for an interactive exchange import, so the bar advances per chunk instead
 * of freezing on one giant transaction (the same trade-off bulk CSV imports make — see
 * `BULK_ENGINE_BATCH_SIZE`). Batch runs with no one watching keep the atomic default.
 */
const val API_ENGINE_BATCH_SIZE = 250

// -------------------------------------------------------------------------------------------------
// Import: parse stored responses into trades + transfers and hand one ImportBatch to the engine.
// -------------------------------------------------------------------------------------------------

/** Outcome of an exchange import. */
data class ExchangeImportResult(
    val tradesImported: Int,
    val transfersImported: Int,
    val duplicatesSkipped: Int,
)

/**
 * Imports a downloaded exchange session: parses stored responses per data-endpoint kind, auto-creates
 * crypto assets, and builds one [ImportBatch] of cross-asset trades + deposit/withdrawal transfers.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
suspend fun importApiSessionExchange(
    apiSessionRepository: ApiSessionReadRepository,
    accountRepository: AccountReadRepository,
    currencyRepository: CurrencyReadRepository,
    cryptoRepository: CryptoReadRepository,
    sessionId: ApiSessionId,
    strategy: ApiImportStrategy,
    importEngine: ImportEngine,
    onProgress: (suspend (Progress) -> Unit)? = null,
    engineBatchSize: Int = Int.MAX_VALUE,
): ExchangeImportResult {
    val synthetic =
        strategy.config.accounts as? ApiAccountsSource.Single
            ?: error("Exchange strategy '${strategy.name}' doesn't import into a single account")
    val source = Source.Api(sessionId)
    val bar = PhasedProgress(onProgress)

    val parsed = parseSessionResponses(apiSessionRepository, sessionId, strategy, bar)

    val fiat = currencyRepository.getAllCurrencies().first()
    val existingCrypto = cryptoRepository.getAllCryptoAssets().first()
    val fiatByCode = fiat.associateBy { it.code.uppercase() }
    val existingCryptoCodes = existingCrypto.mapTo(mutableSetOf()) { it.code.uppercase() }
    val assetCodeByName = lazy { codesByName(fiat.map { it.code to it.name } + existingCrypto.map { it.code to it.name }) }
    val assetAliases = cryptoRepository.assetAliasMap()

    // Normalize provider-specific asset codes before any currency/crypto lookup — strip a sub-holding
    // suffix (Kraken Earn "XETH.F" -> "XETH"), then resolve aliases ("XXBT" -> "BTC") — so every
    // form of one asset resolves to it instead of failing resolution and silently dropping the item.
    fun canonicalAsset(code: String): String =
        resolveAssetCode(strategy.config.assetCodes.canonical(code), assetAliases, assetCodeByName) {
            it in fiatByCode ||
                it in existingCryptoCodes
        }
    val ledgerReconciled = reconcileTradesAgainstLedger(parsed.trades, parsed.ledgerTradeLegs, ::canonicalAsset)
    val trades = ledgerReconciled.trades
    // Enrichment endpoints (e.g. Kraken DepositStatus/WithdrawStatus) supply on-chain address/network/
    // txid for transfers built from a different endpoint (Ledgers), matched by joinKey; a transfer's own
    // fields win when present.
    val enrichByKey = parsed.enrichments.associateBy { it.id }
    val transfers =
        (parsed.transfers + ledgerReconciled.fundingTransfers).map { tx ->
            val enrichment = tx.joinKey?.let { enrichByKey[it] }
            val aliased = tx.copy(currencyCode = canonicalAsset(tx.currencyCode))
            if (enrichment == null) {
                aliased
            } else {
                aliased.copy(
                    counterpartyAddress = aliased.counterpartyAddress ?: enrichment.address,
                    network = aliased.network ?: enrichment.network,
                    txid = aliased.txid ?: enrichment.txid,
                )
            }
        }
    // An order can surface in several download windows (created in one, updated in another); keep the
    // freshest snapshot per order id.
    val orders =
        parsed.orders
            .groupBy { it.orderRef }
            .values
            .map { snapshots -> snapshots.maxBy { it.updatedAt ?: it.createdAt } }

    bar.emit(base = BUILD_BASE, detail = "Resolving assets")
    // Resolve assets: any code that isn't a known fiat currency is treated as a crypto asset and
    // auto-created (the same rule as the CSV importer), sized to the precision the data actually uses.
    val cryptoCodes = mutableSetOf<String>()

    fun noteCrypto(code: String?) {
        if (code == null || code.uppercase() in fiatByCode) return
        cryptoCodes += code.uppercase()
    }
    for (t in trades) {
        noteCrypto(t.baseCode)
        noteCrypto(t.quoteCode)
        noteCrypto(t.feeCode)
    }
    for (tx in transfers) noteCrypto(tx.currencyCode)

    for (code in cryptoCodes) {
        importEngine.createCrypto(code, name = CryptoRegistry.nameFor(code), source = source)
    }

    val cryptoByCode = cryptoRepository.getAllCryptoAssets().first().associateBy { it.code.uppercase() }

    fun asset(code: String): Asset? = fiatByCode[code.uppercase()] ?: cryptoByCode[code.uppercase()]

    // Resolve attribute types and the accounts (synthetic + fee + funding) in a first batch so the
    // trades/transfers below can reference real account ids (the engine creates trades before accounts).
    val exchangeAcctAttr = importEngine.getOrCreateAttributeType(EXCHANGE_ACCOUNT_EXTERNAL_ID_ATTR)
    val txnIdAttr = importEngine.getOrCreateAttributeType(EXCHANGE_TXN_ID_ATTR)
    val walletAddrAttr = importEngine.getOrCreateAttributeType(WALLET_ADDRESS_ATTR)
    val txidAttr = importEngine.getOrCreateAttributeType(BLOCKCHAIN_TXID_ATTR)
    val unidentifiedCounterpartyAttr =
        importEngine.getOrCreateAttributeType(WellKnownIds.UNIDENTIFIED_COUNTERPARTY_ATTR_TYPE_NAME)
    val approximateTimestampAttr = importEngine.getOrCreateAttributeType(WellKnownIds.TIMESTAMP_APPROXIMATE_ATTR_TYPE_NAME)

    bar.emit(base = BUILD_BASE + 0.05f, detail = "Resolving accounts")
    val syntheticKey = LocalAccountKey("exchange-synthetic")
    val feeKey = LocalAccountKey("exchange-fees")
    val fundingKey = LocalAccountKey("exchange-funding")
    val openingDate =
        (trades.map { it.timestamp } + transfers.map { it.timestamp }).minOrNull() ?: Clock.System.now()

    // An account the session creates for its records is sourced from the first record that needs it, so the session that
    // created it is recorded (an API source names a request) and unimporting that session deletes it once empty.
    fun recordSource(tx: ParsedExchangeTransfer) = Source.Api(sessionId, tx.requestId, JsonPath(tx.jsonPath))

    // The generic funding account is the far end only of a movement that names neither a wallet address nor an account (see
    // the counterparty accounts below), so it exists only when one does: an account no movement uses would outlive
    // unimporting whatever else had made it.
    val fundingRecord =
        transfers.firstOrNull { tx ->
            val txAsset = asset(tx.currencyCode)
            txAsset != null && !tx.isFeeOnly && tx.counterpartyAccountName == null &&
                (txAsset !is CryptoAsset || tx.counterpartyAddress.isNullOrBlank())
        }
    val accountResult =
        importEngine.import(
            ImportBatch(
                accountsToCreate =
                    listOfNotNull(
                        // Found by its external id, else it adopts the same-named account a CSV export created (both
                        // name it "Crypto.com Exchange") and tags it, so the account is the same whichever imports first.
                        ImportAccountIntent(
                            key = syntheticKey,
                            source = source,
                            match = AccountMatchKey.ByExternalId(exchangeAcctAttr, synthetic.externalId),
                            name = synthetic.name,
                            openingDate = openingDate,
                            attributes = listOf(NewAttribute(exchangeAcctAttr, synthetic.externalId)),
                            adoptOnBankMatch = true,
                        ),
                        ImportAccountIntent(
                            key = feeKey,
                            source = source,
                            match = AccountMatchKey.FeeAccountOf(AccountRef.Local(syntheticKey)),
                            openingDate = openingDate,
                        ),
                        fundingRecord?.let {
                            ImportAccountIntent(
                                key = fundingKey,
                                source = recordSource(it),
                                match = AccountMatchKey.ByName("${synthetic.name} Funding"),
                                name = "${synthetic.name} Funding",
                                openingDate = openingDate,
                            )
                        },
                    ),
            ),
        )
    val syntheticId = requireNotNull(accountResult.createdAccountIds[syntheticKey])
    val feeAccountId = requireNotNull(accountResult.createdAccountIds[feeKey])
    val fundingId = accountResult.createdAccountIds[fundingKey]

    bar.emit(base = BUILD_BASE + 0.10f, detail = "Building trades and transfers")
    val tradeIntents = mutableListOf<ImportTradeIntent>()
    val transferIntents = mutableListOf<ImportTransfer>()
    // Fill-trade keys per order id, collected only for trades that actually became intents (a trade
    // skipped for an unresolvable asset must not surface as a dangling link key).
    val tradeKeysByOrderRef = mutableMapOf<String, MutableList<LocalTradeKey>>()
    // Records are booked with the attributes their rules write (an `Attribute` tag, an `excluded` value), and a
    // re-download clears an owned one again once the rules stop writing it (only the values those rules can write,
    // never a hand-set one).
    val excludedAttr = AttributeTypeId(WellKnownIds.EXCLUDED_ATTR_TYPE_ID)
    val attributeTypes = mutableMapOf(EXCLUDED_ATTRIBUTE_TYPE_NAME to excludedAttr)
    val ownedAttributes =
        strategy.config.dataEndpoints
            .flatMap { it.transactionMappings?.rules.orEmpty() + it.tradeMappings?.rules.orEmpty() }
            .ownedAttributeValues()
            .entries
            .associate { (typeName, values) ->
                attributeTypes.getOrPut(typeName) { importEngine.getOrCreateAttributeType(typeName) } to values
            }

    suspend fun ruleAttributes(
        excludedValue: String?,
        tags: List<RuleAttribute>,
    ): List<NewAttribute> =
        listOfNotNull(excludedValue?.let { NewAttribute(excludedAttr, it) }) +
            tags.map { tag ->
                NewAttribute(attributeTypes.getOrPut(tag.typeName) { importEngine.getOrCreateAttributeType(tag.typeName) }, tag.value)
            }

    trades.forEach forEachTrade@{ t ->
        val baseAsset = asset(t.baseCode)
        val quoteAsset = asset(t.quoteCode)
        if (baseAsset == null || quoteAsset == null) return@forEachTrade
        val baseMoney = Money.fromDisplayValue(t.baseQuantity, baseAsset)
        val quoteMoney = moneyExact(t.quoteAmount, quoteAsset, "Trade ${t.id} quote leg")
        val description = tradeDescription(t)
        // BUY: quote leaves, base arrives. SELL: base leaves, quote arrives. Both on the one account.
        val fromAmount = if (t.isBuy) quoteMoney else baseMoney
        val toAmount = if (t.isBuy) baseMoney else quoteMoney
        val tradeKey = LocalTradeKey("api-$sessionId-trade-${t.id}")
        tradeIntents +=
            ImportTradeIntent(
                key = tradeKey,
                source = Source.Api(sessionId, t.requestId, JsonPath(t.jsonPath)),
                timestamp = t.timestamp,
                description = description,
                fromAccountId = syntheticId,
                fromAmount = fromAmount,
                toAccountId = syntheticId,
                toAmount = toAmount,
                attributes = ruleAttributes(t.excludedValue, t.ruleAttributes),
                ownedAttributes = ownedAttributes,
                owner = ObservationOwnerKey.Record(ImportRecordKey.ApiItem(sessionId, t.requestId, t.jsonPath)),
            )
        t.orderId?.let { tradeKeysByOrderRef.getOrPut(it, ::mutableListOf).add(tradeKey) }
        // A trade carries no fee slot, so emit any fee as its own movement to the fee account.
        val feeCode = t.feeCode
        val feeAmount = t.feeAmount
        if (feeCode != null && feeAmount != null) {
            val feeAsset = asset(feeCode)
            // A fee whose precision exceeds the asset's scale fails the import (no silent rounding);
            // a genuinely zero fee is dropped.
            val feeMoney = feeAsset?.let { moneyExact(feeAmount, it, "Trade ${t.id} fee") }
            if (feeAsset != null && feeMoney != null && !feeMoney.isZero()) {
                transferIntents +=
                    exchangeTransfer(
                        id = "${t.id}-fee",
                        timestamp = t.timestamp,
                        description = "$description fee",
                        fromAccount = AccountRef.Existing(syntheticId),
                        toAccount = AccountRef.Existing(feeAccountId),
                        money = feeMoney,
                        source = source,
                        requestId = t.requestId,
                        jsonPath = t.jsonPath,
                        txnIdAttr = txnIdAttr,
                    ).let {
                        it.copy(
                            attributes = it.attributes + ruleAttributes(t.excludedValue, emptyList()),
                            ownedAttributes = ownedAttributes,
                            observationSlot = ObservationSlot.FEE,
                        )
                    }
            }
        }
    }

    // Counterparty accounts. Each distinct blockchain address on a CRYPTO-denominated transfer becomes
    // its own wallet account (keyed by the address); a
    // missing address, or any address on a FIAT transfer, falls back to the generic funding account. The
    // fiat exclusion matters because a provider's "address"-shaped field can carry something else entirely
    // for fiat movements — e.g. Kraken's WithdrawStatus `info` is a blockchain address for a crypto
    // withdrawal but the user's own bank-transfer label (e.g. "Monzo") for a fiat one, and minting a wallet
    // account from that label collides by name with the user's real bank account. Failing that, a
    // counterparty the rules name books against that named account (Binance Earn/Bank; the Crypto.com App
    // account for an internal transfer, whose rules clear the address, so the same movement recorded by the
    // CSV export reconciles to it), and only a row naming nothing lands in the generic funding account.
    val accountKeys = mutableMapOf<String, LocalAccountKey>()
    val counterpartyIntents = mutableListOf<ImportAccountIntent>()
    // Only a name some movement books to becomes an account: a crypto movement with an address books to its wallet instead,
    // and an account no movement uses would outlive unimporting whatever else had made it.
    val namedRecords =
        transfers.filter { tx ->
            val txAsset = asset(tx.currencyCode)
            txAsset != null && !tx.isFeeOnly && tx.counterpartyAccountName != null &&
                (txAsset !is CryptoAsset || tx.counterpartyAddress.isNullOrBlank())
        }
    namedRecords
        .groupBy { requireNotNull(it.counterpartyAccountName) }
        .forEach { (name, records) ->
            val key = LocalAccountKey("alias-$name")
            accountKeys[name] = key
            counterpartyIntents +=
                ImportAccountIntent(
                    key = key,
                    source = recordSource(records.first()),
                    match = AccountMatchKey.ByName(name),
                    name = name,
                    openingDate = openingDate,
                )
        }
    transfers
        .filter { asset(it.currencyCode) is CryptoAsset }
        .mapNotNull { it.counterpartyAddress?.takeIf(String::isNotBlank) }
        .distinct()
        .forEach { address ->
            val key = LocalAccountKey("wallet-$address")
            accountKeys[address] = key
            val network = transfers.firstOrNull { it.counterpartyAddress == address && it.network != null }?.network
            counterpartyIntents +=
                ImportAccountIntent(
                    key = key,
                    source = recordSource(transfers.first { it.counterpartyAddress == address }),
                    match = AccountMatchKey.ByExternalId(walletAddrAttr, address),
                    name = if (network != null) "$network:$address" else address,
                    openingDate = openingDate,
                    attributes = listOf(NewAttribute(walletAddrAttr, address)),
                )
        }

    // A charge this feed splits out of a movement the other source folds in: the fee row `parseTransfers`
    // emitted for "<id>" is keyed "<id>-fee", so the main row's gross total is its own amount plus that
    // fee. Only a same-currency charge counts — a fee paid in another asset is a movement of that asset,
    // not part of this one — and a refund (direction IN) is not a charge at all.
    val feeByParentId =
        transfers
            .filter { it.isFeeOnly && it.direction == TransferDirection.OUT && it.id.endsWith("-fee") }
            .groupBy({ it.id.removeSuffix("-fee") }, { it })
    val exchangeRef = AccountRef.Existing(syntheticId)
    for (tx in transfers) {
        val txAsset = asset(tx.currencyCode) ?: continue // single jump — allowed
        val money = Money.fromDisplayValue(tx.amount, txAsset)
        // Set when the far end is only the generic funding account: the exchange could not say where the
        // money came from or went to.
        var unidentifiedCounterparty: AccountId? = null
        val (from, to) =
            if (tx.isFeeOnly) {
                // A refund (negated ledger fee, e.g. crediting back a failed withdrawal's charge) runs the
                // opposite way: Fees -> exchange, not exchange -> Fees.
                if (tx.direction == TransferDirection.IN) {
                    AccountRef.Existing(feeAccountId) to exchangeRef
                } else {
                    exchangeRef to AccountRef.Existing(feeAccountId)
                }
            } else {
                // Only look up a wallet account for a crypto-denominated transfer — see the minting
                // comment above. Guarded explicitly (not just relying on accountKeys missing the entry)
                // so a fiat transfer can never resolve a wallet account a same-valued crypto address
                // happened to mint.
                val counterpartyKey =
                    tx.counterpartyAddress
                        ?.takeIf { txAsset is CryptoAsset && it.isNotBlank() }
                        ?.let { accountKeys[it] }
                        // The counterparty the rules name (e.g. Binance's Earn wallet or its fiat
                        // on/off-ramp) — a named account, so the same movement seen from the other side
                        // reconciles to it instead of everything piling into one funding account.
                        ?: tx.counterpartyAccountName?.let { accountKeys[it] }
                val counterparty =
                    counterpartyKey?.let { AccountRef.Local(it) }
                        ?: AccountRef.Existing(requireNotNull(fundingId)).also { unidentifiedCounterparty = fundingId }
                if (tx.direction == TransferDirection.IN) counterparty to exchangeRef else exchangeRef to counterparty
            }
        val grossMoney =
            if (tx.isFeeOnly) {
                null
            } else {
                feeByParentId[tx.id]
                    ?.filter { it.currencyCode == tx.currencyCode }
                    ?.fold(tx.amount) { total, fee -> total + fee.amount }
                    ?.takeIf { it != tx.amount }
                    ?.let { Money.fromDisplayValue(it, txAsset) }
            }
        transferIntents +=
            exchangeTransfer(
                id = tx.id,
                timestamp = tx.timestamp,
                description = tx.description,
                fromAccount = from,
                toAccount = to,
                money = money,
                grossMoney = grossMoney,
                source = source,
                requestId = tx.requestId,
                jsonPath = tx.jsonPath,
                txnIdAttr = txnIdAttr,
                txid = tx.txid,
                txidAttr = txidAttr,
            ).let { transfer ->
                transfer.copy(
                    // The exchange account is every movement's own end: the feed is its statement.
                    statementAccount = exchangeRef,
                    observationSlot = tx.observationSlot,
                    // A bank export naming this exchange records the same deposit/withdrawal with its real far
                    // end (the user's own bank account); this lets it reconcile against that record instead of
                    // both debiting (or crediting) the exchange.
                    unidentifiedCounterpartyAccountId = unidentifiedCounterparty,
                    approximateUntil = tx.approximateUntil,
                    attributes =
                        transfer.attributes +
                            listOfNotNull(
                                tx.approximateUntil?.let {
                                    NewAttribute(approximateTimestampAttr, it.toEpochMilliseconds().toString())
                                },
                            ) +
                            ruleAttributes(tx.excludedValue, tx.ruleAttributes),
                    ownedAttributes = ownedAttributes,
                )
            }
    }

    // Internal-transfer reconciliation: bridge the exchange account to any configured app account
    // (e.g. the CSV "Crypto.com" App account) so App<->Exchange transfers collapse into one movement.
    val bridges =
        strategy.internalTransferBridges(
            syntheticId,
            accountRepository
                .getAllAccounts()
                .first()
                .reversed()
                .associate { it.name to it.id },
        )

    val orderIntents =
        orders.map { o ->
            ImportOrderIntent(
                key = LocalOrderKey("api-$sessionId-order-${o.orderRef}"),
                source = Source.Api(sessionId, o.requestId, JsonPath(o.jsonPath)),
                accountId = syntheticId,
                orderRef = o.orderRef,
                clientOid = o.clientOid,
                side = o.side,
                orderType = o.orderType,
                timeInForce = o.timeInForce,
                status = o.status,
                limitPrice = o.limitPrice,
                quantity = o.quantity,
                avgPrice = o.avgPrice,
                createdAt = o.createdAt,
                updatedAt = o.updatedAt,
                tradeKeys = tradeKeysByOrderRef[o.orderRef].orEmpty(),
            )
        }

    // A balance is the exchange account's, in the item's own asset as every other code is resolved: a ledger row reports
    // what the account holds after it, whatever the row itself books. An asset no movement made isn't created for it.
    val reportedBalances =
        parsed.balances.mapNotNull { balance ->
            val balanceAsset = asset(canonicalAsset(balance.currencyCode)) ?: return@mapNotNull null
            ImportReportedBalance(
                record = ImportRecordKey.ApiItem(sessionId, balance.requestId, balance.jsonPath),
                account = exchangeRef,
                timestamp = balance.timestamp,
                amount = Money.fromDisplayValue(balance.amount, balanceAsset),
            )
        }

    val result =
        importEngine.import(
            ImportBatch(
                transfers = transferIntents,
                trades = tradeIntents,
                orders = orderIntents,
                reportedBalances = reportedBalances,
                accountsToCreate = counterpartyIntents,
                dedupePolicy = strategy.dedupePolicy(unidentifiedCounterpartyAttr, bridges),
                // An export folds an order's partial fills into one row; this feed reports them one by
                // one. Without a fuzzy trade policy every fill of an already-imported order is booked
                // again (see TradeReconciler's fan-in matching).
                // (Its conversionRelationshipTypeName default also lets a trade match the transfer
                // legs an export books for a conversion it cannot attribute - a dust sweep this feed
                // reports as trades would otherwise be booked a second time.)
                tradeDedupePolicy = strategy.tradeDedupePolicy(),
                apiIdExtractor =
                    ExistingApiIdExtractor { transfer ->
                        transfer.attributes.firstOrNull { it.attributeType.id == txnIdAttr }?.value
                    },
            ),
            onProgress = bar.sink(base = WRITE_BASE, span = WRITE_SPAN),
            batchSize = engineBatchSize,
        )
    val folded = importEngine.syncSessionMatches(apiSessionRepository, sessionId)
    return ExchangeImportResult(
        tradesImported = tradeIntents.size - folded.trades,
        transfersImported = result.transfersImported - folded.transfers,
        duplicatesSkipped = result.duplicates + folded.total,
    )
}

private fun exchangeTransfer(
    id: String,
    timestamp: Instant,
    description: String,
    fromAccount: AccountRef,
    toAccount: AccountRef,
    money: Money,
    source: Source,
    requestId: ApiRequestId,
    jsonPath: String,
    txnIdAttr: AttributeTypeId,
    txid: String? = null,
    txidAttr: AttributeTypeId? = null,
    grossMoney: Money? = null,
): ImportTransfer {
    val txidAttribute = if (txid != null && txidAttr != null) NewAttribute(txidAttr, txid) else null
    return ImportTransfer(
        source = source,
        rowKey = ImportRowKey.ApiJsonPath(requestId, jsonPath),
        fromAccount = fromAccount,
        toAccount = toAccount,
        timestamp = timestamp,
        description = description,
        amount = money,
        // The exchange's own id keys re-imports (skip). The on-chain txid is stored as an attribute for
        // cross-source reconciliation — NOT as a dedupe uniqueKey, which would skip (rather than link) an
        // opposite leg that shares the same txid.
        apiId = id,
        attributes = listOfNotNull(NewAttribute(txnIdAttr, id), txidAttribute),
        reconcileGrossAmount = grossMoney,
    )
}

// No order-derived suffix: existing databases were imported with plain "<verb> BASE/QUOTE"
// descriptions, and trade dedupe is a full-tuple match including description — changing this format
// would duplicate every trade on re-import. Order type/status live on the linked exchange_order.
private fun tradeDescription(trade: ParsedTrade): String {
    val verb = if (trade.isBuy) "Buy" else "Sell"
    return "$verb ${trade.baseCode}/${trade.quoteCode}"
}

/** Converts an exchange-reported display value exactly; throws if the asset's scale can't
 * represent it, so precision is never silently lost. */
private fun moneyExact(
    displayValue: BigDecimal,
    asset: Asset,
    context: String,
): Money =
    try {
        Money.fromDisplayValue(displayValue.abs(), asset)
    } catch (e: ArithmeticException) {
        throw IllegalArgumentException(
            "$context: $displayValue ${asset.code} cannot be represented exactly at scale ${asset.scaleFactor}",
            e,
        )
    }

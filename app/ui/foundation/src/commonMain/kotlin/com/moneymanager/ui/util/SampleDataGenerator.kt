package com.moneymanager.ui.util

import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.importengineapi.AccountMatchKey
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.BatchRelationship
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.ImportAccountIntent
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportCategoryIntent
import com.moneymanager.importengineapi.ImportCryptoIntent
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportFee
import com.moneymanager.importengineapi.ImportOrderIntent
import com.moneymanager.importengineapi.ImportOwnershipIntent
import com.moneymanager.importengineapi.ImportPassThrough
import com.moneymanager.importengineapi.ImportPersonIntent
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTradeIntent
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.importengineapi.LocalCategoryKey
import com.moneymanager.importengineapi.LocalCryptoKey
import com.moneymanager.importengineapi.LocalOrderKey
import com.moneymanager.importengineapi.LocalPersonKey
import com.moneymanager.importengineapi.LocalTradeKey
import com.moneymanager.importengineapi.PersonMatchKey
import com.moneymanager.importengineapi.getOrCreateAttributeTypes
import com.moneymanager.importengineapi.normalizeNameKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

// Transfers are written one chunk per database transaction so the engine reports fine-grained progress
// instead of freezing on one giant transaction for the hundreds of thousands of rows this generates.
private const val SAMPLE_IMPORT_BATCH_SIZE = 5000

private const val CONVERSION_RELATIONSHIP_TYPE_NAME = "conversion"

// An order and its fills must be imported together (the engine resolves the fills' keys), so this is a
// count of orders rather than of rows.
private const val ORDER_IMPORT_CHUNK_SIZE = 10

// Rough shares of observed wall-clock for the three write phases; see GenerationProgress.fraction.
private const val TRANSFER_PHASE_WEIGHT = 0.6f
private const val TRADE_ORDER_PHASE_WEIGHT = 0.25f
private const val REFRESH_PHASE_WEIGHT = 0.15f

/**
 * How much data [generateSampleData] produces. SMALL still covers every feature (crypto, trades,
 * orders, fees, pass-throughs, conversions); LARGE additionally stresses the app with a database the
 * size of a heavy real-world user's.
 */
enum class SampleDataSize(
    val label: String,
    val accountCount: Int,
    private val heavyRange: IntRange,
    private val mediumRange: IntRange,
    private val lightRange: IntRange,
    val cryptoTransferCount: Int,
    val passThroughCount: Int,
    val conversionPairCount: Int,
    val tradeCount: Int,
    val standaloneOrderCount: Int,
) {
    // Trades and orders are written one row per transaction by the engine, so they cost roughly a
    // second each — far more than a transfer. Their counts stay small even in LARGE.
    SMALL("Small", 15, 1000..2000, 300..800, 50..150, 200, 60, 40, 60, 6),
    LARGE("Large", 100, 5000..10000, 1000..3000, 100..500, 2000, 400, 200, 400, 20),
    ;

    /** Transaction count for one account: 20% of accounts are heavy, 50% medium, the rest light. */
    fun transactionCount(
        accountIndex: Int,
        random: Random,
    ): Int {
        val range =
            when {
                accountIndex < accountCount / 5 -> heavyRange
                accountIndex < accountCount * 7 / 10 -> mediumRange
                else -> lightRange
            }
        return random.nextInt(range.first, range.last + 1)
    }
}

// Named accounts that exist so the batch can exercise the features that need dedicated counterparties.
private val EXCHANGE_ACCOUNT_NAMES = listOf("Crypto.com Exchange", "Kraken Spot", "Binance Spot")
private val WALLET_ACCOUNT_NAMES = listOf("Ledger Cold Wallet", "MetaMask Wallet")
private val CONDUIT_ACCOUNT_NAMES = listOf("Curve", "PayPal")
private val MERCHANT_ACCOUNT_NAMES = listOf("Tesco", "Amazon", "Uber", "Steam", "Apple")
private const val CONVERSION_ACCOUNT_NAME = "Crypto.com Conversion"
private const val FEE_ACCOUNT_NAME = "Bank Fees"

private val CRYPTO_ASSETS =
    listOf(
        "BTC" to "Bitcoin",
        "ETH" to "Ethereum",
        "SOL" to "Solana",
        "USDC" to "USD Coin",
        "USDT" to "Tether",
        "DOGE" to "Dogecoin",
        "ADA" to "Cardano",
        "CRO" to "Cronos",
        "SHIB" to "Shiba Inu",
    )

/**
 * Generates sample data through the central [ImportEngine] — the sole DB writer — so every entity is
 * created exactly the way a real import creates it (with `Source.SampleGenerator` provenance).
 *
 * The batches deliberately populate the whole [ImportBatch] surface that a real import can produce:
 * categories, people, accounts, ownerships, crypto assets, transfers (plain, fee-bearing,
 * pass-through chains, conversion pairs, excluded, unique-keyed, fiat and crypto denominated),
 * trades, and exchange orders (fully filled, partially filled and unfilled).
 */
suspend fun generateSampleData(
    currencyRepository: CurrencyReadRepository,
    importEngine: ImportEngine,
    maintenance: Maintenance,
    progressFlow: MutableStateFlow<GenerationProgress>,
    size: SampleDataSize = SampleDataSize.LARGE,
) {
    val random = Random.Default
    val sampleSource = Source.SampleGenerator

    progressFlow.emit(GenerationProgress(size = size, currentOperation = "Fetching currencies..."))

    val currencies = selectCurrencies(currencyRepository)

    // Step 1: categories. Children need their parent's real id, so parents go in first.
    val categoryHierarchy = generateCategoryHierarchy()
    val totalCategories = categoryHierarchy.sumOf { 1 + it.children.size }
    progressFlow.emit(
        GenerationProgress(size = size, totalCategories = totalCategories, currentOperation = "Creating categories..."),
    )
    val categoryIds = createCategories(importEngine, categoryHierarchy, sampleSource)

    // Step 2: crypto assets, so their ids are available to denominate crypto transfers and trades.
    progressFlow.emit(
        GenerationProgress(
            size = size,
            categoriesCreated = totalCategories,
            totalCategories = totalCategories,
            currentOperation = "Creating crypto assets...",
        ),
    )
    val cryptoAssets = createCryptoAssets(importEngine, sampleSource)

    val accounts = AccountPlan(size)
    val baseProgress =
        GenerationProgress(
            size = size,
            categoriesCreated = totalCategories,
            totalCategories = totalCategories,
            cryptoAssetsCreated = cryptoAssets.size,
            totalAccounts = accounts.totalAccounts,
        )

    // Step 3: people, accounts, ownerships.
    progressFlow.emit(baseProgress.copy(currentOperation = "Preparing accounts..."))

    val peopleIntents = buildPeopleIntents(sampleSource)
    val accountIntents = accounts.intents(random, categoryIds, sampleSource)
    val ownershipIntents = buildOwnershipIntents(accounts.allKeys, peopleIntents, random, sampleSource)

    // Step 4: attribute types (no engine creation path; resolved up front like real importers do).
    progressFlow.emit(baseProgress.copy(currentOperation = "Creating attribute types..."))
    val attributeTypeIdsByName = importEngine.getOrCreateAttributeTypes(ATTRIBUTE_TYPE_NAMES)
    val attributeTypeIds = ATTRIBUTE_TYPE_NAMES.map { attributeTypeIdsByName.getValue(it) }

    // Step 5: transfers.
    val transactionCounts = List(size.accountCount) { size.transactionCount(it, random) }
    val totalExpectedTransactions =
        transactionCounts.sum() + size.cryptoTransferCount + size.passThroughCount + size.conversionPairCount * 3
    progressFlow.emit(
        baseProgress.copy(
            totalTransactions = totalExpectedTransactions,
            currentOperation = "Generating transactions...",
        ),
    )

    val transfers =
        TransferBuilder(
            random = random,
            size = size,
            accounts = accounts,
            currencies = currencies,
            cryptoAssets = cryptoAssets,
            attributeTypeIds = attributeTypeIds,
            source = sampleSource,
        ).build(transactionCounts)

    // Step 6: hand the whole batch to the import engine — the single writer for accounts, people,
    // ownerships and transfers. No deduplication: every generated transfer is imported.
    val result =
        importEngine.import(
            batch =
                ImportBatch(
                    transfers = transfers,
                    dedupePolicy = DedupePolicy.None,
                    accountsToCreate = accountIntents,
                    peopleToCreate = peopleIntents,
                    ownerships = ownershipIntents,
                    relationshipTypeNames = listOf(CONVERSION_RELATIONSHIP_TYPE_NAME),
                ),
            onProgress = { progress ->
                val processed = progress.processed
                progressFlow.emit(
                    baseProgress.copy(
                        // Accounts are created before the transfer-write phase, so by the time the engine
                        // reports processed counts every account exists.
                        accountsCreated = if (processed != null) accounts.totalAccounts else 0,
                        transactionsCreated = processed ?: 0,
                        totalTransactions = progress.total ?: totalExpectedTransactions,
                        currentOperation =
                            if (processed != null) {
                                "Created $processed/${progress.total} transactions..."
                            } else {
                                progress.detail
                            },
                    ),
                )
            },
            batchSize = SAMPLE_IMPORT_BATCH_SIZE,
        )

    val transfersProgress =
        baseProgress.copy(
            accountsCreated = accounts.totalAccounts,
            transactionsCreated = result.transfersImported,
            totalTransactions = totalExpectedTransactions,
        )

    // Step 7: trades and orders. Both reference real account ids, so they can only be built once the
    // accounts of the previous batch exist.
    val exchangeAccountIds = accounts.exchangeKeys.map { result.createdAccountIds.getValue(it) }
    val (trades, orders) = buildTradesAndOrders(random, size, exchangeAccountIds, currencies, cryptoAssets, sampleSource)

    val tradesProgress = transfersProgress.copy(totalTrades = trades.size, totalOrders = orders.size)
    progressFlow.emit(tradesProgress.copy(currentOperation = "Creating trades and orders..."))

    // Imported a chunk of orders at a time (each with its own fills, which an order must be batched
    // with) so this phase reports progress instead of leaving the dialog pinned while it runs.
    val tradesByKey = trades.associateBy { it.key }
    var tradesCreated = 0
    var ordersCreated = 0
    for (orderChunk in orders.chunked(ORDER_IMPORT_CHUNK_SIZE)) {
        val chunkTrades = orderChunk.flatMap { order -> order.tradeKeys.map { tradesByKey.getValue(it) } }
        importEngine.import(ImportBatch(trades = chunkTrades, orders = orderChunk))
        tradesCreated += chunkTrades.size
        ordersCreated += orderChunk.size
        progressFlow.emit(
            tradesProgress.copy(
                tradesCreated = tradesCreated,
                ordersCreated = ordersCreated,
                currentOperation = "Created $ordersCreated/${orders.size} orders with $tradesCreated/${trades.size} fills...",
            ),
        )
    }

    val refreshProgress =
        tradesProgress.copy(
            tradesCreated = trades.size,
            ordersCreated = orders.size,
        )
    progressFlow.emit(refreshProgress.copy(currentOperation = "Refreshing balances (this takes a moment)..."))
    maintenance.refreshMaterializedViews()

    progressFlow.emit(refreshProgress.copy(refreshed = true, currentOperation = "Sample data generation complete!"))
}

/**
 * A random amount between [minMajor] and [maxMajor] major units, built directly in the asset's own
 * minor units so it is exact for every scale factor — 1 (JPY), 100 (USD), 1000 (BHD) and 10^18
 * (crypto). Constructing a fixed 2-decimal [com.moneymanager.bigdecimal.BigDecimal] and converting it
 * would throw "Rounding necessary" on a zero-decimal currency.
 */
internal fun randomMoney(
    random: Random,
    asset: Asset,
    minMajor: Long,
    maxMajor: Long,
): Money {
    val major = random.nextLong(minMajor, maxMajor + 1)
    // Zero-decimal assets have no minor part; everything else gets a random fraction, which for crypto
    // means a full 18 significant decimals.
    val fraction = if (asset.scaleFactor > 1) random.nextLong(1, asset.scaleFactor) else 0L
    val minorUnits = BigInteger(major) * BigInteger(asset.scaleFactor) + BigInteger(fraction)
    return Money(minorUnits, asset)
}

private suspend fun selectCurrencies(currencyRepository: CurrencyReadRepository): List<Currency> {
    val allCurrencies = currencyRepository.getAllCurrencies().first()
    check(allCurrencies.isNotEmpty()) { "No currencies found in database. Please create currencies first." }

    // Deliberately spans 0-decimal (JPY, KRW), 2-decimal and 3-decimal (BHD, KWD) currencies so the
    // generated data exercises every scale factor.
    val popularCurrencyCodes =
        listOf(
            "USD",
            "EUR",
            "GBP",
            "JPY",
            "CAD",
            "AUD",
            "CHF",
            "CNY",
            "INR",
            "MXN",
            "BRL",
            "KRW",
            "SGD",
            "NZD",
            "SEK",
            "NOK",
            "DKK",
            "PLN",
            "BHD",
            "KWD",
        )
    val selected =
        allCurrencies
            .filter { it.code in popularCurrencyCodes }
            .ifEmpty { allCurrencies.take(20) }

    check(selected.isNotEmpty()) { "No currencies available for sample data generation." }
    return selected
}

private suspend fun createCategories(
    importEngine: ImportEngine,
    hierarchy: List<CategoryWithChildren>,
    source: Source,
): List<Long> {
    val parentKeys = hierarchy.indices.map { LocalCategoryKey("category-parent-$it") }
    val parentResult =
        importEngine.import(
            ImportBatch.manualEdits(
                categories =
                    hierarchy.mapIndexed { index, parent ->
                        ImportCategoryIntent(key = parentKeys[index], source = source, name = parent.category.name)
                    },
            ),
        )
    val parentIds = parentKeys.map { parentResult.createdCategoryIds.getValue(it) }

    val childIntents =
        hierarchy.flatMapIndexed { parentIndex, parent ->
            parent.children.mapIndexed { childIndex, child ->
                ImportCategoryIntent(
                    key = LocalCategoryKey("category-child-$parentIndex-$childIndex"),
                    source = source,
                    name = child.name,
                    parentId = parentIds[parentIndex],
                )
            }
        }
    val childResult = importEngine.import(ImportBatch.manualEdits(categories = childIntents))

    return parentIds + childIntents.map { childResult.createdCategoryIds.getValue(it.key) }
}

private suspend fun createCryptoAssets(
    importEngine: ImportEngine,
    source: Source,
): List<CryptoAsset> {
    val intents =
        CRYPTO_ASSETS.map { (code, name) ->
            ImportCryptoIntent(key = LocalCryptoKey("crypto-$code"), source = source, code = code, name = name)
        }
    val result = importEngine.import(ImportBatch(cryptoAssets = intents))
    return intents.mapIndexed { index, intent ->
        val (code, name) = CRYPTO_ASSETS[index]
        CryptoAsset(id = result.createdCryptoIds.getValue(intent.key), code = code, name = name)
    }
}

/** The accounts of the batch: the generic ones plus the named ones each feature needs. */
private class AccountPlan(
    size: SampleDataSize,
) {
    val genericNames = generateAccountNames(size.accountCount)
    val genericKeys = genericNames.indices.map { LocalAccountKey("acct-$it") }
    val exchangeKeys = EXCHANGE_ACCOUNT_NAMES.indices.map { LocalAccountKey("exchange-$it") }
    val walletKeys = WALLET_ACCOUNT_NAMES.indices.map { LocalAccountKey("wallet-$it") }
    val conduitKeys = CONDUIT_ACCOUNT_NAMES.indices.map { LocalAccountKey("conduit-$it") }
    val merchantKeys = MERCHANT_ACCOUNT_NAMES.indices.map { LocalAccountKey("merchant-$it") }
    val conversionKey = LocalAccountKey("conversion")
    val feeKey = LocalAccountKey("fees")

    private val namesByKey: Map<LocalAccountKey, String> =
        buildMap {
            genericKeys.forEachIndexed { index, key -> put(key, genericNames[index]) }
            exchangeKeys.forEachIndexed { index, key -> put(key, EXCHANGE_ACCOUNT_NAMES[index]) }
            walletKeys.forEachIndexed { index, key -> put(key, WALLET_ACCOUNT_NAMES[index]) }
            conduitKeys.forEachIndexed { index, key -> put(key, CONDUIT_ACCOUNT_NAMES[index]) }
            merchantKeys.forEachIndexed { index, key -> put(key, MERCHANT_ACCOUNT_NAMES[index]) }
            put(conversionKey, CONVERSION_ACCOUNT_NAME)
            put(feeKey, FEE_ACCOUNT_NAME)
        }

    val allKeys: List<LocalAccountKey> = namesByKey.keys.toList()
    val totalAccounts: Int get() = allKeys.size

    fun intents(
        random: Random,
        categoryIds: List<Long>,
        source: Source,
    ): List<ImportAccountIntent> {
        val now = Clock.System.now()
        return allKeys.map { key ->
            ImportAccountIntent(
                key = key,
                // The generator always creates fresh accounts; it never reuses existing ones.
                match = AccountMatchKey.AlwaysCreate,
                name = namesByKey.getValue(key),
                openingDate = now.minus(random.nextInt(1, 3650).days),
                source = source,
                categoryId = categoryIds.random(random),
            )
        }
    }
}

private class TransferBuilder(
    private val random: Random,
    private val size: SampleDataSize,
    private val accounts: AccountPlan,
    private val currencies: List<Currency>,
    private val cryptoAssets: List<CryptoAsset>,
    private val attributeTypeIds: List<AttributeTypeId>,
    private val source: Source,
) {
    private val startDate = Instant.parse("2015-01-01T00:00:00Z")
    private val endDate = Instant.parse("2025-12-31T23:59:59Z")
    private val dateRangeMillis = endDate.toEpochMilliseconds() - startDate.toEpochMilliseconds()
    private var rowIndex = 0L

    fun build(transactionCounts: List<Int>): List<ImportTransfer> =
        buildList {
            addAll(plainTransfers(transactionCounts))
            addAll(cryptoTransfers())
            addAll(passThroughTransfers())
            addAll(conversionTransfers())
        }

    /** Row keys deliberately alternate across the three producer kinds so provenance covers each. */
    private fun nextRowKey(): ImportRowKey {
        val index = rowIndex++
        return when (index % 3) {
            0L -> ImportRowKey.CsvRow(index)
            1L -> ImportRowKey.QifRecord(index)
            else -> ImportRowKey.Manual(index)
        }
    }

    private fun randomTimestamp(): Instant =
        Instant.fromEpochMilliseconds(startDate.toEpochMilliseconds() + random.nextLong(dateRangeMillis))

    private fun randomAttributes(): List<NewAttribute> =
        if (random.nextBoolean()) {
            attributeTypeIds.shuffled(random).take(random.nextInt(1, 4)).map { typeId ->
                NewAttribute(typeId, generateAttributeValue(random))
            }
        } else {
            emptyList()
        }

    private fun plainTransfers(transactionCounts: List<Int>): List<ImportTransfer> {
        val keys = accounts.genericKeys
        val transfers = ArrayList<ImportTransfer>(transactionCounts.sum())
        for (accountIndex in keys.indices) {
            repeat(transactionCounts[accountIndex]) { transferIndex ->
                var targetIndex = random.nextInt(keys.size - 1)
                if (targetIndex >= accountIndex) targetIndex++

                val currency = currencies.random(random)
                val amount = randomMoney(random, currency, 1, 10_000)
                val rowKey = nextRowKey()

                transfers.add(
                    ImportTransfer(
                        rowKey = rowKey,
                        fromAccount = AccountRef.Local(keys[accountIndex]),
                        toAccount = AccountRef.Local(keys[targetIndex]),
                        source = source,
                        timestamp = randomTimestamp(),
                        description = generateTransactionDescription(random),
                        amount = amount,
                        attributes = randomAttributes(),
                        // A slice of rows carries a bank fee, an importer-style unique key, or is kept
                        // out of balances — the corner cases the transactions UI has to render.
                        fee =
                            if (transferIndex % 17 == 0) {
                                ImportFee(
                                    source = AccountRef.Local(keys[accountIndex]),
                                    target = AccountRef.Local(accounts.feeKey),
                                    amount = randomMoney(random, currency, 1, 5),
                                    description = "Transaction fee",
                                    relationshipTypeId = RelationshipTypeId(WellKnownIds.FEE_RELATIONSHIP_TYPE_ID),
                                    rowKey = rowKey,
                                )
                            } else {
                                null
                            },
                        uniqueKey =
                            if (transferIndex % 11 == 0) {
                                mapOf("Transaction Code" to "TXN-$accountIndex-$transferIndex")
                            } else {
                                null
                            },
                        excludedFromBalances = transferIndex % 23 == 0,
                    ),
                )
            }
        }
        return transfers
    }

    /** Crypto moves between an exchange account and a wallet, denominated in a crypto asset. */
    private fun cryptoTransfers(): List<ImportTransfer> =
        List(size.cryptoTransferCount) {
            val exchange = accounts.exchangeKeys.random(random)
            val wallet = accounts.walletKeys.random(random)
            val asset = cryptoAssets.random(random)
            val withdrawal = random.nextBoolean()
            ImportTransfer(
                rowKey = nextRowKey(),
                fromAccount = AccountRef.Local(if (withdrawal) exchange else wallet),
                toAccount = AccountRef.Local(if (withdrawal) wallet else exchange),
                source = source,
                timestamp = randomTimestamp(),
                description = if (withdrawal) "Withdraw ${asset.code}" else "Deposit ${asset.code}",
                amount = randomMoney(random, asset, 0, 2),
                attributes = randomAttributes(),
            )
        }

    /**
     * Charges routed through conduit accounts: a single-hop chain (card → Curve → merchant), a
     * multi-hop one (card → Curve → PayPal → merchant) and refunds coming back the other way.
     */
    private fun passThroughTransfers(): List<ImportTransfer> =
        List(size.passThroughCount) { index ->
            val card = accounts.genericKeys.random(random)
            val merchant = accounts.merchantKeys.random(random)
            val multiHop = index % 3 == 0
            val incoming = index % 5 == 0
            val conduits = if (multiHop) accounts.conduitKeys else listOf(accounts.conduitKeys.first())
            val currency = currencies.random(random)
            val amount = randomMoney(random, currency, 1, 500)
            val rowKey = nextRowKey()
            val merchantName = MERCHANT_ACCOUNT_NAMES[accounts.merchantKeys.indexOf(merchant)]

            ImportTransfer(
                rowKey = rowKey,
                // The funding leg: card → first conduit, or first conduit → card for a refund.
                fromAccount = AccountRef.Local(if (incoming) conduits.first() else card),
                toAccount = AccountRef.Local(if (incoming) card else conduits.first()),
                source = source,
                timestamp = randomTimestamp(),
                description = if (incoming) "Refund from $merchantName" else "Card payment to $merchantName",
                amount = amount,
                passThrough =
                    ImportPassThrough(
                        conduits = conduits.map { AccountRef.Local(it) },
                        merchantTarget = AccountRef.Local(merchant),
                        amount = amount,
                        spendDescriptions = conduits.indices.map { merchantName },
                        relationshipTypeId = RelationshipTypeId(WellKnownIds.PASS_THROUGH_RELATIONSHIP_TYPE_ID),
                        rowKey = rowKey,
                        incoming = incoming,
                    ),
            )
        }

    /**
     * A conversion (e.g. a crypto.com wallet swap): several debits into a shared conversion account
     * and one credit out of it, linked by an in-batch `conversion` relationship.
     */
    private fun conversionTransfers(): List<ImportTransfer> =
        List(size.conversionPairCount) {
            val wallet = accounts.exchangeKeys.random(random)
            val timestamp = randomTimestamp()
            val debitAssets = cryptoAssets.shuffled(random).take(2)
            val creditAsset = cryptoAssets.random(random)

            val debits =
                debitAssets.map { asset ->
                    ImportTransfer(
                        rowKey = nextRowKey(),
                        fromAccount = AccountRef.Local(wallet),
                        toAccount = AccountRef.Local(accounts.conversionKey),
                        source = source,
                        timestamp = timestamp,
                        description = "Convert ${asset.code}",
                        amount = randomMoney(random, asset, 0, 1),
                    )
                }
            val credit =
                ImportTransfer(
                    rowKey = nextRowKey(),
                    fromAccount = AccountRef.Local(accounts.conversionKey),
                    toAccount = AccountRef.Local(wallet),
                    source = source,
                    timestamp = timestamp,
                    description = "Converted to ${creditAsset.code}",
                    amount = randomMoney(random, creditAsset, 0, 1),
                    batchRelationships =
                        debits.map { debit ->
                            BatchRelationship(
                                relatedRowKey = requireNotNull(debit.rowKey),
                                typeName = CONVERSION_RELATIONSHIP_TYPE_NAME,
                            )
                        },
                )
            debits + credit
        }.flatten()
}

/**
 * Trades (the fills) and the exchange orders they belong to. Orders cover every shape the Orders UI
 * has to render: fully filled from several fills, partially filled, and unfilled (ACTIVE/CANCELED).
 */
private fun buildTradesAndOrders(
    random: Random,
    size: SampleDataSize,
    exchangeAccountIds: List<AccountId>,
    currencies: List<Currency>,
    cryptoAssets: List<CryptoAsset>,
    source: Source,
): Pair<List<ImportTradeIntent>, List<ImportOrderIntent>> {
    val quoteCurrency = currencies.firstOrNull { it.code == "USD" } ?: currencies.first()
    val startDate = Instant.parse("2020-01-01T00:00:00Z")
    val rangeMillis = Instant.parse("2025-12-31T00:00:00Z").toEpochMilliseconds() - startDate.toEpochMilliseconds()

    val trades = ArrayList<ImportTradeIntent>(size.tradeCount)
    val orders = ArrayList<ImportOrderIntent>()

    var tradeIndex = 0
    var orderIndex = 0
    while (tradeIndex < size.tradeCount) {
        val accountId = exchangeAccountIds.random(random)
        val asset = cryptoAssets.random(random)
        val createdAt = Instant.fromEpochMilliseconds(startDate.toEpochMilliseconds() + random.nextLong(rangeMillis))
        val buy = random.nextBoolean()
        val fillCount = minOf(random.nextInt(1, 4), size.tradeCount - tradeIndex)

        val fillKeys =
            List(fillCount) { fill ->
                val key = LocalTradeKey("trade-${tradeIndex++}")
                val cryptoLeg = randomMoney(random, asset, 0, 2)
                val fiatLeg = randomMoney(random, quoteCurrency, 10, 5_000)
                trades.add(
                    ImportTradeIntent(
                        key = key,
                        source = source,
                        timestamp = createdAt.plus((fill + 1).minutes),
                        description =
                            if (buy) {
                                "Buy ${asset.code} fill ${fill + 1}"
                            } else {
                                "Sell ${asset.code} fill ${fill + 1}"
                            },
                        fromAccountId = accountId,
                        toAccountId = accountId,
                        fromAmount = if (buy) fiatLeg else cryptoLeg,
                        toAmount = if (buy) cryptoLeg else fiatLeg,
                    ),
                )
                key
            }

        val limit = orderIndex % 2 == 0
        val partial = orderIndex % 5 == 0
        orders.add(
            ImportOrderIntent(
                key = LocalOrderKey("order-$orderIndex"),
                source = source,
                accountId = accountId,
                orderRef = "SAMPLE-ORDER-$orderIndex",
                // Some venues report no client id; keep both shapes in the data.
                clientOid = if (orderIndex % 3 == 0) null else "client-oid-$orderIndex",
                side = if (buy) "BUY" else "SELL",
                orderType = if (limit) "LIMIT" else "MARKET",
                timeInForce = if (limit) "GOOD_TILL_CANCEL" else null,
                status = if (partial) "PARTIALLY_FILLED" else "FILLED",
                limitPrice = if (limit) randomPrice(random) else null,
                quantity = randomPrice(random),
                avgPrice = randomPrice(random),
                createdAt = createdAt,
                updatedAt = createdAt.plus((fillCount + 1).minutes),
                tradeKeys = fillKeys,
            ),
        )
        orderIndex++
    }

    // Orders that never filled: no linked trades, no average price.
    repeat(size.standaloneOrderCount) { index ->
        val createdAt = Instant.fromEpochMilliseconds(startDate.toEpochMilliseconds() + random.nextLong(rangeMillis))
        orders.add(
            ImportOrderIntent(
                key = LocalOrderKey("order-$orderIndex"),
                source = source,
                accountId = exchangeAccountIds.random(random),
                orderRef = "SAMPLE-ORDER-$orderIndex",
                side = if (index % 2 == 0) "BUY" else "SELL",
                orderType = "LIMIT",
                timeInForce = "FILL_OR_KILL",
                status = if (index % 2 == 0) "ACTIVE" else "CANCELED",
                limitPrice = randomPrice(random),
                quantity = randomPrice(random),
                createdAt = createdAt,
            ),
        )
        orderIndex++
    }

    return trades to orders
}

private fun randomPrice(random: Random): String = "${random.nextInt(1, 60_000)}.${random.nextInt(10, 99)}"

private val ATTRIBUTE_TYPE_NAMES =
    listOf(
        "Reference Number",
        "Merchant ID",
        "Order ID",
        "Invoice Number",
        "Receipt Number",
        "Confirmation Code",
        "Transaction Code",
        "Check Number",
    )

private fun buildPeopleIntents(source: Source): List<ImportPersonIntent> {
    val peopleNames =
        listOf(
            Triple("John", "Michael", "Doe"),
            Triple("Jane", "Elizabeth", "Smith"),
            Triple("Alice", null, "Johnson"),
            Triple("Bob", "James", "Wilson"),
            Triple("Charlie", null, "Brown"),
            Triple("Diana", "Marie", "Prince"),
            Triple("Eve", "Ann", null),
            Triple("Frank", null, "Castle"),
            Triple("Grace", "Lynn", "Harper"),
            Triple("Henry", "David", "Ford"),
        )

    return peopleNames.mapIndexed { index, (firstName, middleName, lastName) ->
        val fullName = listOfNotNull(firstName, middleName, lastName).joinToString(" ")
        ImportPersonIntent(
            key = LocalPersonKey("person-$index"),
            match = PersonMatchKey.ByNameKey(normalizeNameKey(fullName)),
            firstName = firstName,
            middleName = middleName,
            lastName = lastName,
            source = source,
        )
    }
}

/** Most accounts have one owner; some are joint (two owners). */
private fun buildOwnershipIntents(
    accountKeys: List<LocalAccountKey>,
    people: List<ImportPersonIntent>,
    random: Random,
    source: Source,
): List<ImportOwnershipIntent> =
    accountKeys.flatMap { accountKey ->
        val ownerCount = if (random.nextDouble() < 0.15) 2 else 1
        people.shuffled(random).take(ownerCount).map { person ->
            ImportOwnershipIntent(
                personKey = person.key,
                account = AccountRef.Local(accountKey),
                source = source,
            )
        }
    }

private fun generateAccountNames(count: Int): List<String> {
    val prefixes =
        listOf(
            "Personal",
            "Business",
            "Savings",
            "Emergency",
            "Investment",
            "Retirement",
            "Travel",
            "Education",
            "Health",
            "Entertainment",
            "Shopping",
            "Food",
            "Transport",
            "Utilities",
            "Mortgage",
            "Rent",
            "Vacation",
            "Hobby",
            "Charity",
            "Gift",
            "Project",
            "Revenue",
            "Expense",
            "Cash",
        )

    val suffixes =
        listOf(
            "Checking",
            "Savings",
            "Account",
            "Fund",
            "Wallet",
            "Reserve",
            "Portfolio",
            "Budget",
            "Stash",
            "Pool",
            "Treasury",
            "Vault",
        )

    val names = mutableListOf<String>()
    var index = 0
    while (names.size < count) {
        val prefix = prefixes.random()
        val suffix = suffixes.random()
        if (index < count / 2) {
            val name = "$prefix $suffix"
            if (!names.contains(name)) {
                names.add(name)
            }
        } else {
            // Second half: add numbers to ensure uniqueness
            names.add("$prefix $suffix ${(names.size / 10) + 1}")
        }
        index++
    }

    return names.take(count)
}

private fun generateAttributeValue(random: Random): String {
    val prefixes = listOf("REF", "ORD", "INV", "TXN", "CHK", "REC", "CNF", "MID")
    val prefix = prefixes.random(random)
    val number = random.nextInt(100000, 999999)
    return "$prefix-$number"
}

private fun generateTransactionDescription(random: Random): String {
    val descriptions =
        listOf(
            // Income
            "Salary deposit",
            "Freelance payment",
            "Dividend income",
            "Interest earned",
            "Bonus payment",
            "Refund received",
            "Gift received",
            "Cashback reward",
            // Expenses
            "Grocery shopping",
            "Restaurant bill",
            "Gas station",
            "Online shopping",
            "Utility bill",
            "Rent payment",
            "Mortgage payment",
            "Insurance premium",
            "Phone bill",
            "Internet bill",
            "Gym membership",
            "Subscription service",
            "Coffee shop",
            "Taxi fare",
            "Public transport",
            "Parking fee",
            "Medical expense",
            "Pharmacy purchase",
            "Book purchase",
            "Movie ticket",
            "Concert ticket",
            "Hotel booking",
            "Flight ticket",
            "Car maintenance",
            "Home repair",
            "Clothing purchase",
            "Electronics purchase",
            "Gift purchase",
            "Charity donation",
            "ATM withdrawal",
            // Transfers
            "Transfer to savings",
            "Transfer from savings",
            "Account rebalancing",
            "Fund allocation",
            "Investment transfer",
            "Emergency fund deposit",
            "Budget allocation",
        )

    return descriptions.random(random)
}

private data class CategoryWithChildren(
    val category: Category,
    val children: List<Category>,
)

private fun generateCategoryHierarchy(): List<CategoryWithChildren> =
    listOf(
        CategoryWithChildren(
            category = Category(name = "Income"),
            children =
                listOf(
                    Category(name = "Salary"),
                    Category(name = "Freelance"),
                    Category(name = "Investments"),
                    Category(name = "Dividends"),
                    Category(name = "Interest"),
                    Category(name = "Gifts Received"),
                    Category(name = "Refunds"),
                    Category(name = "Other Income"),
                ),
        ),
        CategoryWithChildren(
            category = Category(name = "Housing"),
            children =
                listOf(
                    Category(name = "Rent"),
                    Category(name = "Mortgage"),
                    Category(name = "Property Tax"),
                    Category(name = "Home Insurance"),
                    Category(name = "Maintenance"),
                    Category(name = "Utilities"),
                    Category(name = "HOA Fees"),
                ),
        ),
        CategoryWithChildren(
            category = Category(name = "Transportation"),
            children =
                listOf(
                    Category(name = "Fuel"),
                    Category(name = "Public Transit"),
                    Category(name = "Car Payment"),
                    Category(name = "Car Insurance"),
                    Category(name = "Maintenance & Repairs"),
                    Category(name = "Parking"),
                    Category(name = "Tolls"),
                    Category(name = "Rideshare"),
                ),
        ),
        CategoryWithChildren(
            category = Category(name = "Food & Dining"),
            children =
                listOf(
                    Category(name = "Groceries"),
                    Category(name = "Restaurants"),
                    Category(name = "Coffee Shops"),
                    Category(name = "Fast Food"),
                    Category(name = "Delivery"),
                ),
        ),
        CategoryWithChildren(
            category = Category(name = "Shopping"),
            children =
                listOf(
                    Category(name = "Clothing"),
                    Category(name = "Electronics"),
                    Category(name = "Home Goods"),
                    Category(name = "Personal Care"),
                    Category(name = "Gifts"),
                    Category(name = "Books"),
                    Category(name = "Hobbies"),
                ),
        ),
        CategoryWithChildren(
            category = Category(name = "Entertainment"),
            children =
                listOf(
                    Category(name = "Streaming Services"),
                    Category(name = "Movies & Theater"),
                    Category(name = "Concerts & Events"),
                    Category(name = "Sports"),
                    Category(name = "Gaming"),
                    Category(name = "Subscriptions"),
                ),
        ),
        CategoryWithChildren(
            category = Category(name = "Health & Fitness"),
            children =
                listOf(
                    Category(name = "Medical"),
                    Category(name = "Dental"),
                    Category(name = "Vision"),
                    Category(name = "Pharmacy"),
                    Category(name = "Gym Membership"),
                    Category(name = "Health Insurance"),
                ),
        ),
        CategoryWithChildren(
            category = Category(name = "Personal"),
            children =
                listOf(
                    Category(name = "Hair & Beauty"),
                    Category(name = "Clothing"),
                    Category(name = "Education"),
                    Category(name = "Professional Development"),
                    Category(name = "Legal"),
                ),
        ),
        CategoryWithChildren(
            category = Category(name = "Travel"),
            children =
                listOf(
                    Category(name = "Flights"),
                    Category(name = "Hotels"),
                    Category(name = "Car Rental"),
                    Category(name = "Vacation"),
                    Category(name = "Business Travel"),
                ),
        ),
        CategoryWithChildren(
            category = Category(name = "Financial"),
            children =
                listOf(
                    Category(name = "Bank Fees"),
                    Category(name = "Investment Fees"),
                    Category(name = "Tax Preparation"),
                    Category(name = "Financial Advisor"),
                    Category(name = "Insurance Premiums"),
                ),
        ),
        CategoryWithChildren(
            category = Category(name = "Family"),
            children =
                listOf(
                    Category(name = "Childcare"),
                    Category(name = "Child Support"),
                    Category(name = "Pet Care"),
                    Category(name = "Allowance"),
                    Category(name = "Gifts to Family"),
                ),
        ),
        CategoryWithChildren(
            category = Category(name = "Bills & Utilities"),
            children =
                listOf(
                    Category(name = "Electricity"),
                    Category(name = "Water"),
                    Category(name = "Gas"),
                    Category(name = "Internet"),
                    Category(name = "Phone"),
                    Category(name = "Cable/Satellite"),
                ),
        ),
        CategoryWithChildren(
            category = Category(name = "Savings & Investments"),
            children =
                listOf(
                    Category(name = "Emergency Fund"),
                    Category(name = "Retirement"),
                    Category(name = "Stocks"),
                    Category(name = "Bonds"),
                    Category(name = "Real Estate"),
                    Category(name = "Cryptocurrency"),
                ),
        ),
        CategoryWithChildren(
            category = Category(name = "Charity"),
            children =
                listOf(
                    Category(name = "Religious Organizations"),
                    Category(name = "Nonprofits"),
                    Category(name = "Education"),
                    Category(name = "Community"),
                ),
        ),
        CategoryWithChildren(
            category = Category(name = "Business"),
            children =
                listOf(
                    Category(name = "Office Supplies"),
                    Category(name = "Software"),
                    Category(name = "Marketing"),
                    Category(name = "Professional Services"),
                    Category(name = "Business Travel"),
                    Category(name = "Equipment"),
                ),
        ),
    )

data class GenerationProgress(
    val size: SampleDataSize = SampleDataSize.LARGE,
    val accountsCreated: Int = 0,
    val totalAccounts: Int = 0,
    val categoriesCreated: Int = 0,
    val totalCategories: Int = 0,
    val cryptoAssetsCreated: Int = 0,
    val transactionsCreated: Int = 0,
    val totalTransactions: Int = 0,
    val tradesCreated: Int = 0,
    val totalTrades: Int = 0,
    val ordersCreated: Int = 0,
    val totalOrders: Int = 0,
    val refreshed: Boolean = false,
    val currentOperation: String = "Initializing...",
) {
    /**
     * Overall progress across all three write phases. Transfers dominate but do not finish the job —
     * counting only them left the bar pinned at 100% while trades, orders and the balance refresh
     * still ran. The weights are rough shares of observed wall-clock.
     */
    val fraction: Float
        get() {
            val transfers = if (totalTransactions > 0) transactionsCreated.toFloat() / totalTransactions else 0f
            val tradesAndOrders =
                if (totalTrades + totalOrders > 0) {
                    (tradesCreated + ordersCreated).toFloat() / (totalTrades + totalOrders)
                } else {
                    0f
                }
            val refresh = if (refreshed) 1f else 0f
            return transfers * TRANSFER_PHASE_WEIGHT +
                tradesAndOrders * TRADE_ORDER_PHASE_WEIGHT +
                refresh * REFRESH_PHASE_WEIGHT
        }
}

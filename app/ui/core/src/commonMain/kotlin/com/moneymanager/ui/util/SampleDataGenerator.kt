@file:OptIn(ExperimentalTime::class)

package com.moneymanager.ui.util

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.importengineapi.AccountMatchKey
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.ImportAccountIntent
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportCategoryIntent
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportOwnershipIntent
import com.moneymanager.importengineapi.ImportPersonIntent
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.importengineapi.LocalCategoryKey
import com.moneymanager.importengineapi.LocalPersonKey
import com.moneymanager.importengineapi.PersonMatchKey
import com.moneymanager.importengineapi.getOrCreateAttributeTypes
import com.moneymanager.importengineapi.normalizeNameKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// Transfers are written one chunk per database transaction so the engine reports fine-grained progress
// instead of freezing on one giant transaction for the hundreds of thousands of rows this generates.
private const val SAMPLE_IMPORT_BATCH_SIZE = 5000

private const val ACCOUNT_COUNT = 100

/**
 * Generates sample data through the central [ImportEngine] — the sole DB writer for entities and
 * transfers — so accounts, people, ownerships and transactions are created exactly the way a real
 * import creates them (with `Source.SampleGenerator` provenance). Attribute types are resolved
 * (get-or-create) through the engine up front and then referenced by id from the batch.
 */
suspend fun generateSampleData(
    currencyRepository: CurrencyReadRepository,
    importEngine: ImportEngine,
    maintenance: Maintenance,
    progressFlow: MutableStateFlow<GenerationProgress>,
) {
    val random = Random.Default
    val sampleSource = Source.SampleGenerator

    // Step 1: Fetch currencies
    progressFlow.emit(GenerationProgress(currentOperation = "Fetching currencies..."))

    val allCurrencies = currencyRepository.getAllCurrencies().first()
    check(allCurrencies.isNotEmpty()) { "No currencies found in database. Please create currencies first." }

    // Pick 10-20 popular currencies (or all if less than 20)
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
            "THB",
            "ZAR",
        )

    val selectedCurrencies =
        allCurrencies
            .filter { currency ->
                popularCurrencyCodes.contains(currency.code)
            }.take(20)
            .ifEmpty { allCurrencies.take(20) }

    check(selectedCurrencies.isNotEmpty()) { "No currencies available for sample data generation." }

    // Step 2: Generate and create categories with hierarchical structure. Categories have no import-engine
    // creation path, so they are created directly and then referenced by id from the account intents.
    progressFlow.emit(GenerationProgress(currentOperation = "Generating categories..."))

    val categoryHierarchy = generateCategoryHierarchy()
    val totalCategories = categoryHierarchy.sumOf { 1 + it.children.size }

    progressFlow.emit(
        GenerationProgress(
            totalCategories = totalCategories,
            currentOperation = "Creating $totalCategories categories...",
        ),
    )

    val categoryIds = mutableListOf<Long>()
    var categoriesCreated = 0

    for (parent in categoryHierarchy) {
        val parentKey = LocalCategoryKey("parent")
        val parentId =
            importEngine
                .import(
                    ImportBatch.manualEdits(
                        categories =
                            listOf(
                                ImportCategoryIntent(
                                    key = parentKey,
                                    source = sampleSource,
                                    name = parent.category.name,
                                    parentId = parent.category.parentId,
                                ),
                            ),
                    ),
                ).createdCategoryIds
                .getValue(parentKey)
        categoryIds.add(parentId)
        categoriesCreated++

        progressFlow.emit(
            GenerationProgress(
                categoriesCreated = categoriesCreated,
                totalCategories = totalCategories,
                currentOperation = "Creating categories...",
            ),
        )

        for (child in parent.children) {
            val childKey = LocalCategoryKey("child")
            val childId =
                importEngine
                    .import(
                        ImportBatch.manualEdits(
                            categories =
                                listOf(
                                    ImportCategoryIntent(
                                        key = childKey,
                                        source = sampleSource,
                                        name = child.name,
                                        parentId = parentId,
                                    ),
                                ),
                        ),
                    ).createdCategoryIds
                    .getValue(childKey)
            categoryIds.add(childId)
            categoriesCreated++

            progressFlow.emit(
                GenerationProgress(
                    categoriesCreated = categoriesCreated,
                    totalCategories = totalCategories,
                    currentOperation = "Creating categories...",
                ),
            )
        }
    }

    val baseProgress =
        GenerationProgress(
            categoriesCreated = categoriesCreated,
            totalCategories = totalCategories,
        )

    // Step 3: People intents
    progressFlow.emit(baseProgress.copy(currentOperation = "Preparing people..."))

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

    val peopleIntents =
        peopleNames.mapIndexed { index, (firstName, middleName, lastName) ->
            val fullName = listOfNotNull(firstName, middleName, lastName).joinToString(" ")
            ImportPersonIntent(
                key = LocalPersonKey("person-$index"),
                match = PersonMatchKey.ByNameKey(normalizeNameKey(fullName)),
                firstName = firstName,
                middleName = middleName,
                lastName = lastName,
                source = sampleSource,
            )
        }

    // Step 4: Account intents with category assignments
    progressFlow.emit(baseProgress.copy(currentOperation = "Preparing accounts..."))

    val now = Clock.System.now()
    val accountNames = generateAccountNames()
    val accountKeys = List(accountNames.size) { LocalAccountKey("acct-$it") }
    val accountIntents =
        accountNames.mapIndexed { index, name ->
            ImportAccountIntent(
                key = accountKeys[index],
                // The generator always creates fresh accounts; it never reuses existing ones.
                match = AccountMatchKey.AlwaysCreate,
                name = name,
                openingDate = now.minus(random.nextInt(1, 3650).days),
                source = sampleSource,
                categoryId = categoryIds.random(random),
            )
        }

    // Step 5: Ownership intents — most accounts have one owner, some are joint (two owners).
    val ownershipIntents =
        accountKeys.flatMap { accountKey ->
            val ownerCount = if (random.nextDouble() < 0.15) 2 else 1
            peopleIntents.shuffled(random).take(ownerCount).map { person ->
                ImportOwnershipIntent(
                    personKey = person.key,
                    account = AccountRef.Local(accountKey),
                    source = sampleSource,
                )
            }
        }

    // Step 6: Attribute types (no engine creation path; resolved up front like real importers do).
    progressFlow.emit(baseProgress.copy(currentOperation = "Creating attribute types..."))

    val attributeTypeNames =
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

    val attributeTypeIdsByName = importEngine.getOrCreateAttributeTypes(attributeTypeNames)
    val attributeTypeIds = attributeTypeNames.map { attributeTypeIdsByName.getValue(it) }

    // Step 7: Determine transaction counts per account (variable distribution)
    val transactionCounts =
        List(ACCOUNT_COUNT) { i ->
            when {
                i < 20 -> random.nextInt(5000, 10001) // 20% get 5000-10000 transactions
                i < 70 -> random.nextInt(1000, 3001) // 50% get 1000-3000 transactions
                else -> random.nextInt(100, 501) // 30% get 100-500 transactions
            }
        }
    val totalExpectedTransactions = transactionCounts.sum()

    progressFlow.emit(
        baseProgress.copy(
            totalTransactions = totalExpectedTransactions,
            currentOperation = "Generating transactions with attributes...",
        ),
    )

    // Step 8: Generate transfers referencing batch-local accounts.
    val startDate = Instant.parse("2015-01-01T00:00:00Z")
    val endDate = Instant.parse("2025-12-31T23:59:59Z")
    val dateRangeMillis = endDate.toEpochMilliseconds() - startDate.toEpochMilliseconds()

    val transfers = ArrayList<ImportTransfer>(totalExpectedTransactions)
    var rowIndex = 0L
    for (accountIndex in accountKeys.indices) {
        val transactionCount = transactionCounts[accountIndex]
        repeat(transactionCount) {
            // Random timestamp between 2015-2025
            val randomMillis = random.nextLong(dateRangeMillis)
            val timestamp = Instant.fromEpochMilliseconds(startDate.toEpochMilliseconds() + randomMillis)

            // Random target account (different from source)
            var targetIndex = random.nextInt(accountKeys.size - 1)
            if (targetIndex >= accountIndex) targetIndex++

            // Random currency
            val currency = selectedCurrencies.random(random)

            // Random amount (1-10000 with 2 decimal places)
            val amountCents = random.nextInt(100, 1000001)
            val amount = BigDecimal("${amountCents / 100}.${(amountCents % 100).toString().padStart(2, '0')}")

            // 50% of transactions get 1-3 attributes
            val attributes: List<NewAttribute> =
                if (random.nextBoolean()) {
                    val numAttributes = random.nextInt(1, 4) // 1-3 attributes
                    attributeTypeIds.shuffled(random).take(numAttributes).map { typeId ->
                        NewAttribute(typeId, generateAttributeValue(random))
                    }
                } else {
                    emptyList()
                }

            transfers.add(
                ImportTransfer(
                    rowKey = ImportRowKey.CsvRow(rowIndex++),
                    fromAccount = AccountRef.Local(accountKeys[accountIndex]),
                    toAccount = AccountRef.Local(accountKeys[targetIndex]),
                    source = sampleSource,
                    timestamp = timestamp,
                    description = generateTransactionDescription(random),
                    amount = Money.fromDisplayValue(amount, currency),
                    attributes = attributes,
                ),
            )
        }
    }

    // Step 9: Hand the whole batch to the import engine — the single writer for accounts, people,
    // ownerships and transfers. No deduplication: every generated transfer is imported.
    val batch =
        ImportBatch(
            transfers = transfers,
            dedupePolicy = DedupePolicy.None,
            accountsToCreate = accountIntents,
            peopleToCreate = peopleIntents,
            ownerships = ownershipIntents,
        )

    importEngine.import(
        batch = batch,
        onProgress = { progress ->
            val processed = progress.processed
            progressFlow.emit(
                baseProgress.copy(
                    // Accounts are created before the transfer-write phase, so by the time the engine
                    // reports processed counts every account exists.
                    accountsCreated = if (processed != null) ACCOUNT_COUNT else baseProgress.accountsCreated,
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

    // Refresh materialized views
    progressFlow.emit(
        baseProgress.copy(
            accountsCreated = ACCOUNT_COUNT,
            transactionsCreated = totalExpectedTransactions,
            totalTransactions = totalExpectedTransactions,
            currentOperation = "Refreshing materialized views...",
        ),
    )
    maintenance.refreshMaterializedViews()

    // Final progress update
    progressFlow.emit(
        baseProgress.copy(
            accountsCreated = ACCOUNT_COUNT,
            transactionsCreated = totalExpectedTransactions,
            totalTransactions = totalExpectedTransactions,
            currentOperation = "Sample data generation complete!",
        ),
    )
}

private fun generateAccountNames(): List<String> {
    val count = ACCOUNT_COUNT
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

    // Generate unique names
    var index = 0
    while (names.size < count) {
        if (index < count / 2) {
            // First half: Prefix + Suffix
            val prefix = prefixes.random()
            val suffix = suffixes.random()
            val name = "$prefix $suffix"
            if (!names.contains(name)) {
                names.add(name)
            }
        } else {
            // Second half: Add numbers to ensure uniqueness
            val prefix = prefixes.random()
            val suffix = suffixes.random()
            val number = (names.size / 10) + 1
            names.add("$prefix $suffix $number")
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
    val accountsCreated: Int = 0,
    val totalAccounts: Int = ACCOUNT_COUNT,
    val categoriesCreated: Int = 0,
    val totalCategories: Int = 0,
    val transactionsCreated: Int = 0,
    val totalTransactions: Int = 0,
    val currentOperation: String = "Initializing...",
)

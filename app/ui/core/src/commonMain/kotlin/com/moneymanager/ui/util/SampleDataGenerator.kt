@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.util

import com.moneymanager.database.DatabaseMaintenanceService
import com.moneymanager.database.SampleGeneratorSourceRecorder
import com.moneymanager.database.sql.TransferSourceQueries
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AttributeTypeRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipRepository
import com.moneymanager.domain.repository.PersonRepository
import com.moneymanager.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

suspend fun generateSampleData(
    currencyRepository: CurrencyRepository,
    categoryRepository: CategoryRepository,
    accountRepository: AccountRepository,
    personRepository: PersonRepository,
    personAccountOwnershipRepository: PersonAccountOwnershipRepository,
    attributeTypeRepository: AttributeTypeRepository,
    transactionRepository: TransactionRepository,
    maintenanceService: DatabaseMaintenanceService,
    transferSourceQueries: TransferSourceQueries,
    deviceId: DeviceId,
    progressFlow: MutableStateFlow<GenerationProgress>,
) {
    val random = Random.Default

    // Step 1: Fetch currencies
    progressFlow.emit(
        GenerationProgress(
            currentOperation = "Fetching currencies...",
        ),
    )

    val allCurrencies = currencyRepository.getAllCurrencies().first()
    check(allCurrencies.isNotEmpty()) { "No currencies found in database. Please create currencies first." }

    // Pick 10-20 popular currencies (or all if less than 20)
    val popularCurrencyCodes =
        listOf(
            "USD", "EUR", "GBP", "JPY", "CAD", "AUD", "CHF", "CNY", "INR", "MXN",
            "BRL", "KRW", "SGD", "NZD", "SEK", "NOK", "DKK", "PLN", "THB", "ZAR",
        )

    val selectedCurrencies =
        allCurrencies.filter { currency ->
            popularCurrencyCodes.contains(currency.code)
        }.take(20).ifEmpty { allCurrencies.take(20) }

    check(selectedCurrencies.isNotEmpty()) { "No currencies available for sample data generation." }

    // Step 2: Generate and create categories with hierarchical structure
    progressFlow.emit(
        GenerationProgress(
            currentOperation = "Generating categories...",
        ),
    )

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
        val parentId = categoryRepository.createCategory(parent.category)
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
            val childId =
                categoryRepository.createCategory(
                    child.copy(parentId = parentId),
                )
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

    // Step 2.5: Generate people
    progressFlow.emit(
        GenerationProgress(
            categoriesCreated = categoriesCreated,
            totalCategories = totalCategories,
            currentOperation = "Creating people...",
        ),
    )

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

    val personIds = mutableListOf<PersonId>()
    for ((firstName, middleName, lastName) in peopleNames) {
        val person =
            Person(
                id = PersonId(0),
                firstName = firstName,
                middleName = middleName,
                lastName = lastName,
            )
        val personId = personRepository.createPerson(person)
        personIds.add(personId)
    }

    progressFlow.emit(
        GenerationProgress(
            categoriesCreated = categoriesCreated,
            totalCategories = totalCategories,
            currentOperation = "Created ${personIds.size} people",
        ),
    )

    // Step 3: Generate account names
    progressFlow.emit(
        GenerationProgress(
            categoriesCreated = categoriesCreated,
            totalCategories = totalCategories,
            currentOperation = "Generating account names...",
        ),
    )

    val accountNames = generateAccountNames(100)

    // Step 4: Determine transaction counts per account (variable distribution)
    val transactionCounts = mutableListOf<Int>()
    for (i in 0 until 100) {
        val count =
            when {
                i < 20 -> random.nextInt(5000, 10001) // 20% get 5000-10000 transactions
                i < 70 -> random.nextInt(1000, 3001) // 50% get 1000-3000 transactions
                else -> random.nextInt(100, 501) // 30% get 100-500 transactions
            }
        transactionCounts.add(count)
    }

    val totalExpectedTransactions = transactionCounts.sum()

    progressFlow.emit(
        GenerationProgress(
            categoriesCreated = categoriesCreated,
            totalCategories = totalCategories,
            totalAccounts = 100,
            totalTransactions = totalExpectedTransactions,
            currentOperation = "Creating accounts...",
        ),
    )

    // Step 5: Create accounts in batch with category assignments
    val now = Clock.System.now()

    progressFlow.emit(
        GenerationProgress(
            categoriesCreated = categoriesCreated,
            totalCategories = totalCategories,
            totalAccounts = 100,
            totalTransactions = totalExpectedTransactions,
            currentOperation = "Creating 100 accounts in batch...",
        ),
    )

    val accountsToCreate =
        accountNames.map { name ->
            Account(
                id = AccountId(0),
                name = name,
                openingDate = now.minus(random.nextInt(1, 3650).days),
                categoryId = categoryIds.random(random),
            )
        }

    val accountIds = accountRepository.createAccountsBatch(accountsToCreate)

    progressFlow.emit(
        GenerationProgress(
            categoriesCreated = categoriesCreated,
            totalCategories = totalCategories,
            accountsCreated = 100,
            totalAccounts = 100,
            totalTransactions = totalExpectedTransactions,
            currentOperation = "Created all 100 accounts",
        ),
    )

    // Step 5.5: Assign owners to accounts
    progressFlow.emit(
        GenerationProgress(
            categoriesCreated = categoriesCreated,
            totalCategories = totalCategories,
            accountsCreated = 100,
            totalAccounts = 100,
            totalTransactions = totalExpectedTransactions,
            currentOperation = "Assigning owners to accounts...",
        ),
    )

    for (accountId in accountIds) {
        // Most accounts have 1 owner, some have 2 (joint accounts)
        val ownerCount = if (random.nextDouble() < 0.15) 2 else 1
        val selectedOwners = personIds.shuffled(random).take(ownerCount)

        for (personId in selectedOwners) {
            personAccountOwnershipRepository.createOwnership(
                personId = personId,
                accountId = accountId,
            )
        }
    }

    progressFlow.emit(
        GenerationProgress(
            categoriesCreated = categoriesCreated,
            totalCategories = totalCategories,
            accountsCreated = 100,
            totalAccounts = 100,
            totalTransactions = totalExpectedTransactions,
            currentOperation = "Assigned owners to all accounts",
        ),
    )

    // Step 6: Create sample attribute types first (needed for generating attributes)
    progressFlow.emit(
        GenerationProgress(
            categoriesCreated = categoriesCreated,
            totalCategories = totalCategories,
            accountsCreated = 100,
            totalAccounts = 100,
            totalTransactions = totalExpectedTransactions,
            currentOperation = "Creating attribute types...",
        ),
    )

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

    val attributeTypeIds = mutableListOf<AttributeTypeId>()
    for (typeName in attributeTypeNames) {
        val typeId = attributeTypeRepository.getOrCreate(typeName)
        attributeTypeIds.add(typeId)
    }

    // Step 7: Generate transactions with attributes
    progressFlow.emit(
        GenerationProgress(
            categoriesCreated = categoriesCreated,
            totalCategories = totalCategories,
            accountsCreated = 100,
            totalAccounts = 100,
            totalTransactions = totalExpectedTransactions,
            currentOperation = "Generating transactions with attributes...",
        ),
    )

    val startDate = Instant.parse("2015-01-01T00:00:00Z")
    val endDate = Instant.parse("2025-12-31T23:59:59Z")
    val dateRangeMillis = endDate.toEpochMilliseconds() - startDate.toEpochMilliseconds()

    // Generate all transactions with their attributes
    val allTransfers = mutableListOf<Transfer>()
    val allNewAttributes = mutableMapOf<TransferId, List<NewAttribute>>()

    for ((accountIndex, accountId) in accountIds.withIndex()) {
        val transactionCount = transactionCounts[accountIndex]

        repeat(transactionCount) { _ ->
            // Random timestamp between 2015-2025
            val randomMillis = random.nextLong(dateRangeMillis)
            val timestamp = Instant.fromEpochMilliseconds(startDate.toEpochMilliseconds() + randomMillis)

            // Random target account (different from source)
            val targetAccountId = accountIds.filter { it != accountId }.random(random)

            // Random currency
            val currency = selectedCurrencies.random(random)

            // Random amount (1-10000 with 2 decimal places)
            val amount = (random.nextInt(100, 1000001) / 100.0)

            // Random description
            val description = generateTransactionDescription(random)

            // Placeholder ID - real ID generated by database
            val transfer =
                Transfer(
                    id = TransferId(0L),
                    timestamp = timestamp,
                    description = description,
                    sourceAccountId = accountId,
                    targetAccountId = targetAccountId,
                    amount = Money.fromDisplayValue(amount, currency),
                )

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

            allTransfers.add(transfer)
            if (attributes.isNotEmpty()) {
                allNewAttributes[transfer.id] = attributes
            }
        }
    }

    // Step 8: Create transactions with attributes and sources in batches
    var transactionsCreated = 0

    transactionRepository.createTransfers(
        transfers = allTransfers,
        newAttributes = allNewAttributes,
        sourceRecorder = SampleGeneratorSourceRecorder(transferSourceQueries, deviceId),
        onProgress = { created, total ->
            transactionsCreated = created
            progressFlow.emit(
                GenerationProgress(
                    categoriesCreated = categoriesCreated,
                    totalCategories = totalCategories,
                    accountsCreated = 100,
                    totalAccounts = 100,
                    transactionsCreated = created,
                    totalTransactions = total,
                    currentOperation = "Created $created/$total transactions...",
                ),
            )
        },
    )

    // Refresh materialized views
    progressFlow.emit(
        GenerationProgress(
            categoriesCreated = categoriesCreated,
            totalCategories = totalCategories,
            accountsCreated = 100,
            totalAccounts = 100,
            transactionsCreated = transactionsCreated,
            totalTransactions = totalExpectedTransactions,
            currentOperation = "Refreshing materialized views...",
        ),
    )
    maintenanceService.refreshMaterializedViews()

    // Final progress update
    progressFlow.emit(
        GenerationProgress(
            categoriesCreated = categoriesCreated,
            totalCategories = totalCategories,
            accountsCreated = 100,
            totalAccounts = 100,
            transactionsCreated = transactionsCreated,
            totalTransactions = totalExpectedTransactions,
            currentOperation = "Sample data generation complete!",
        ),
    )
}

private fun generateAccountNames(count: Int): List<String> {
    val prefixes =
        listOf(
            "Personal", "Business", "Savings", "Emergency", "Investment", "Retirement",
            "Travel", "Education", "Health", "Entertainment", "Shopping", "Food",
            "Transport", "Utilities", "Mortgage", "Rent", "Vacation", "Hobby",
            "Charity", "Gift", "Project", "Revenue", "Expense", "Cash",
        )

    val suffixes =
        listOf(
            "Checking", "Savings", "Account", "Fund", "Wallet", "Reserve",
            "Portfolio", "Budget", "Stash", "Pool", "Treasury", "Vault",
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

private fun generateCategoryHierarchy(): List<CategoryWithChildren> {
    return listOf(
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
}

data class GenerationProgress(
    val accountsCreated: Int = 0,
    val totalAccounts: Int = 100,
    val categoriesCreated: Int = 0,
    val totalCategories: Int = 0,
    val transactionsCreated: Int = 0,
    val totalTransactions: Int = 0,
    val currentOperation: String = "Initializing...",
)

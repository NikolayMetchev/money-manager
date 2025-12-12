@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.util

import com.moneymanager.database.RepositorySet
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

data class GenerationProgress(
    val accountsCreated: Int = 0,
    val totalAccounts: Int = 100,
    val categoriesCreated: Int = 0,
    val totalCategories: Int = 0,
    val transactionsCreated: Int = 0,
    val totalTransactions: Int = 0,
    val currentOperation: String = "Initializing...",
)

suspend fun generateSampleData(
    repositorySet: RepositorySet,
    progressFlow: MutableStateFlow<GenerationProgress>,
) {
    val random = Random.Default

    // Step 1: Fetch currencies
    progressFlow.emit(
        GenerationProgress(
            currentOperation = "Fetching currencies...",
        ),
    )

    val allCurrencies = repositorySet.currencyRepository.getAllCurrencies().first()
    if (allCurrencies.isEmpty()) {
        throw IllegalStateException("No currencies found in database. Please create currencies first.")
    }

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

    if (selectedCurrencies.isEmpty()) {
        throw IllegalStateException("No currencies available for sample data generation.")
    }

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
        val parentId = repositorySet.categoryRepository.createCategory(parent.category)
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
                repositorySet.categoryRepository.createCategory(
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

    val accountIds = repositorySet.accountRepository.createAccountsBatch(accountsToCreate)

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

    // Step 6: Generate transactions in batches
    progressFlow.emit(
        GenerationProgress(
            categoriesCreated = categoriesCreated,
            totalCategories = totalCategories,
            accountsCreated = 100,
            totalAccounts = 100,
            totalTransactions = totalExpectedTransactions,
            currentOperation = "Generating transactions...",
        ),
    )

    val startDate = Instant.parse("2015-01-01T00:00:00Z")
    val endDate = Instant.parse("2025-12-31T23:59:59Z")
    val dateRangeMillis = endDate.toEpochMilliseconds() - startDate.toEpochMilliseconds()

    // Generate all transactions first, then insert in batches
    val allTransfers = mutableListOf<Transfer>()

    for ((accountIndex, accountId) in accountIds.withIndex()) {
        val transactionCount = transactionCounts[accountIndex]

        for (txIndex in 0 until transactionCount) {
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

            val transfer =
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = timestamp,
                    description = description,
                    sourceAccountId = accountId,
                    targetAccountId = targetAccountId,
                    amount = Money.fromDisplayValue(amount, currency),
                )

            allTransfers.add(transfer)
        }
    }

    // Insert transfers in batches of 1000 for better progress tracking
    val batchSize = 1000
    var transactionsCreated = 0

    for (batchStart in allTransfers.indices step batchSize) {
        val batchEnd = minOf(batchStart + batchSize, allTransfers.size)
        val batch = allTransfers.subList(batchStart, batchEnd)

        repositorySet.transactionRepository.createTransfersBatch(batch)
        transactionsCreated += batch.size

        progressFlow.emit(
            GenerationProgress(
                categoriesCreated = categoriesCreated,
                totalCategories = totalCategories,
                accountsCreated = 100,
                totalAccounts = 100,
                transactionsCreated = transactionsCreated,
                totalTransactions = totalExpectedTransactions,
                currentOperation = "Inserted $transactionsCreated/$totalExpectedTransactions transactions...",
            ),
        )
    }

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
    repositorySet.maintenanceService.refreshMaterializedViews()

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

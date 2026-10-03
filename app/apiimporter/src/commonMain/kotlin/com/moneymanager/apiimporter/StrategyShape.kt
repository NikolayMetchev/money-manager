package com.moneymanager.apiimporter

import com.moneymanager.domain.model.apistrategy.ApiAccountsSource
import com.moneymanager.domain.model.apistrategy.ApiDataEndpoint
import com.moneymanager.domain.model.apistrategy.ApiStrategyConfig
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings

/** The enumerated-accounts source of a bank strategy; fails for one that imports into a single account. */
internal fun ApiStrategyConfig.downloadedAccounts(): ApiAccountsSource.Downloaded =
    accounts as? ApiAccountsSource.Downloaded ?: error("This strategy imports into a single account; it enumerates none")

/** A bank strategy's transaction feed; fails for a strategy without one. */
internal fun ApiStrategyConfig.bankFeed(): ApiDataEndpoint =
    requireNotNull(bankTransactions) {
        "This strategy has no bank transaction feed"
    }

/** The field mappings of a bank strategy's transaction feed. */
internal fun ApiStrategyConfig.bankFeedMappings(): ApiTransactionMappings =
    requireNotNull(bankFeed().transactionMappings) { "The bank transaction feed has no transaction mappings" }

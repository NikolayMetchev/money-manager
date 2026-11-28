package com.moneymanager.ui.screens.accountmapping

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.regex.PortableRegex

/**
 * Told a mapping's pattern, target account and scope (null = global) once it is saved, so the host can
 * offer to re-import the files whose accounts it now takes over.
 */
typealias OnAccountMappingSaved = (pattern: PortableRegex, accountId: AccountId, strategyId: CsvImportStrategyId?) -> Unit

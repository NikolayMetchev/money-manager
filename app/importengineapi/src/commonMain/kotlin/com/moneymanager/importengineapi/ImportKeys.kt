package com.moneymanager.importengineapi

/**
 * Normalises a full name into a stable matching key (trim, collapse internal whitespace, lower-case).
 * Shared so importers that produce [PersonMatchKey.ByNameKey] derive the same key the engine uses to
 * index existing people.
 */
fun normalizeNameKey(fullName: String): String = fullName.trim().replace(Regex("\\s+"), " ").lowercase()

/**
 * The composite identity for a personal counterparty account — sort code + account number — used as
 * the [AccountMatchKey.ByPersonalCounterparty] key. Shared so importers that emit the match key derive
 * the same string the engine uses to index existing accounts by their sort-code/account-number
 * attributes, keeping cross-provider bank reconciliation order-independent.
 */
fun personalCounterpartyKey(
    sortCode: String,
    accountNumber: String,
): String = "$sortCode|$accountNumber"

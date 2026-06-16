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

/**
 * Derives the [personalCounterpartyKey] from a synthetic bank external-id of the form
 * `bank:<sortCode>:<accountNumber>`, or null for any other id. Lets an account whose only bank identity
 * is such an external-id (created before its sort/account were persisted as attributes) still reconcile
 * against an incoming [AccountMatchKey.ByPersonalCounterparty]. Shared so the engine and importers agree
 * on the synthetic id format.
 */
fun bankKeyFromExternalId(externalId: String?): String? {
    if (externalId == null || !externalId.startsWith("bank:")) return null
    val parts = externalId.removePrefix("bank:").split(":").takeIf { it.size == 2 } ?: return null
    val (sortCode, accountNumber) = parts
    return if (sortCode.isNotBlank() && accountNumber.isNotBlank()) personalCounterpartyKey(sortCode, accountNumber) else null
}

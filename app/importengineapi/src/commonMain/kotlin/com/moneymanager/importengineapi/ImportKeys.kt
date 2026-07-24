package com.moneymanager.importengineapi

import com.moneymanager.domain.model.WellKnownIds.ACCOUNT_ACCOUNT_NUMBER_ATTR_TYPE_ID
import com.moneymanager.domain.model.WellKnownIds.ACCOUNT_SORT_CODE_ATTR_TYPE_ID

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
 *
 * This doubles as the **seed** for an attribute's `group_key`, which is what lets one account own several
 * bank identities (Crypto.com moved its fiat destination to a new sort code, so payments to one account
 * arrive under two). Seeding the group with the identity's own natural key makes a re-import upsert the
 * same group instead of minting a duplicate.
 *
 * The group key is only ever a seed: readers must re-derive the key from the attribute **values** in a
 * group and never parse `group_key` itself. A key can legitimately go stale — a user edits a sort code,
 * or a merge re-keys a collision — and matching has to keep working when it does.
 */
fun personalCounterpartyKey(
    sortCode: String,
    accountNumber: String,
): String = "$sortCode|$accountNumber"

/**
 * Every bank key derivable from [attributes], one per attribute group holding both a sort code and an
 * account number. An account with two bank identities yields two keys, and both must be indexed or the
 * second identity silently matches nothing.
 *
 * Derived from the values, never from the group key. Within the ungrouped group (`""`) a pair is only
 * formed when there is exactly one sort code and exactly one account number, so half-grouped or
 * partially-edited data can never mint a phantom identity by pairing one group's sort code with
 * another's account number.
 */
fun bankKeysFrom(attributes: List<AttributeSlot>): List<String> =
    attributes
        .groupBy { it.groupKey }
        .entries
        .sortedBy { it.key }
        .mapNotNull { (_, group) ->
            val sortCodes = group.filter { it.typeId == ACCOUNT_SORT_CODE_ATTR_TYPE_ID }.map { it.value }
            val accountNumbers = group.filter { it.typeId == ACCOUNT_ACCOUNT_NUMBER_ATTR_TYPE_ID }.map { it.value }
            val sortCode = sortCodes.singleOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val accountNumber = accountNumbers.singleOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            personalCounterpartyKey(sortCode, accountNumber)
        }.distinct()

/**
 * One attribute reduced to what [bankKeysFrom] needs: which type it is, its value, and which group it
 * belongs to. Lets the same pairing logic run over import intents and over persisted rows.
 */
data class AttributeSlot(
    val typeId: Long,
    val value: String,
    val groupKey: String,
)

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

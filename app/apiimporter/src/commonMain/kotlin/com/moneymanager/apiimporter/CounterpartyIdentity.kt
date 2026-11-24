package com.moneymanager.apiimporter

import com.moneymanager.domain.model.apistrategy.ApiPeopleMappings
import com.moneymanager.importengineapi.personalCounterpartyKey
import kotlinx.serialization.json.JsonObject

/** This item's counterparty identity, its user id dropped when the rules call that id throwaway. */
internal fun ApiTransactionPageItem.counterpartyIdentity(
    counterpartyIdField: String?,
    peopleMappings: ApiPeopleMappings,
): String? = rawJson?.resolveCounterpartyIdentity(counterpartyIdField, peopleMappings, ignoreUserId = outcome.ignoreCounterpartyId)

internal fun JsonObject.resolveCounterpartyIdentity(
    counterpartyIdField: String?,
    peopleMappings: ApiPeopleMappings,
    ignoreUserId: Boolean,
): String? {
    // The bank sub-entity (sort code + account number) is the most reliable account identity for
    // providers like Starling, where a single real account can otherwise be split across several
    // counterparty ids. When configured, it wins over the explicit id; the id (and then the name)
    // remain the fallback for counterparties without bank details.
    if (peopleMappings.preferBankIdentity) {
        bankIdentity(peopleMappings)?.let { return it }
    }

    if (counterpartyIdField == null) return null

    resolveJsonPath(counterpartyIdField)?.takeIf { it.isNotBlank() }?.let { return it }

    // counterpartyIdField (e.g. Monzo's "counterparty.id") is often absent while the counterparty's own
    // account id is always present — check it before falling through to the user-id fallback below,
    // which is not stable across a self-transfer (Monzo reports the account holder's own user id there).
    resolveJsonObjectPath(peopleMappings.counterpartyObjectField)
        ?.stringOrNull(peopleMappings.counterpartyAccountIdField)
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }

    bankIdentity(peopleMappings)?.let { return it }

    val counterparty = resolveJsonObjectPath(peopleMappings.counterpartyObjectField) ?: return null
    val serviceUserNumber = counterparty.stringOrNull(peopleMappings.counterpartyServiceUserNumberField)?.takeIf { it.isNotBlank() }
    if (serviceUserNumber != null) {
        return "service_user:$serviceUserNumber"
    }

    val userId = counterparty.stringOrNull(peopleMappings.counterpartyUserIdField)?.takeIf { it.isNotBlank() }
    // An ephemeral user id (e.g. Monzo's per-transaction "anonuser_…") is not a stable identity, so it
    // must not key the counterparty account — leaving it to be matched by name keeps the same real
    // person from fragmenting into one account per transaction.
    if (userId != null && !ignoreUserId) {
        return "user:$userId"
    }

    return null
}

/**
 * The counterparty's bank identity ("bank:<sortCode>:<accountNumber>") when both the sort code and
 * account number are present, else null. Resolved relative to [ApiPeopleMappings.counterpartyObjectField]
 * (blank means the transaction item itself, for providers with flat counterparty fields).
 */
internal fun JsonObject.bankIdentity(peopleMappings: ApiPeopleMappings): String? {
    val counterparty = resolveJsonObjectPath(peopleMappings.counterpartyObjectField) ?: return null
    val sortCode = counterparty.stringOrNull(peopleMappings.counterpartySortCodeField)?.takeIf { it.isNotBlank() } ?: return null
    val accountNumber =
        counterparty.stringOrNull(peopleMappings.counterpartyAccountNumberField)?.takeIf { it.isNotBlank() } ?: return null
    return "bank:$sortCode:$accountNumber"
}

/**
 * Derives the bank dedupe key ("sortCode|accountNumber") from a counterparty id of the form
 * "bank:<sortCode>:<accountNumber>" (produced by [bankIdentity] / [resolveCounterpartyIdentity]), or
 * null for any other id. This lets a counterparty resolved purely by bank identity merge with an
 * account carrying the same sort code + account number even when it is not flagged a personal
 * beneficiary — so every representation of one real bank account collapses to a single account.
 */
internal fun bankDedupeKeyFromCounterpartyId(counterpartyId: String?): String? {
    if (counterpartyId == null || !counterpartyId.startsWith("bank:")) return null
    val (sortCode, accountNumber) = counterpartyId.removePrefix("bank:").split(":").takeIf { it.size == 2 } ?: return null
    return if (sortCode.isNotBlank() && accountNumber.isNotBlank()) {
        personalCounterpartyKey(sortCode, accountNumber)
    } else {
        null
    }
}

/**
 * Splits a bank dedupe key ("sortCode|accountNumber", as produced by [PersonalCounterpartyIdentity.dedupeKey]
 * and [bankDedupeKeyFromCounterpartyId]) back into its sort code + account number, or null if it isn't one.
 * Lets a bank-identified counterparty persist its identity as sort-code/account-number attributes even when
 * it wasn't flagged a personal beneficiary, so a later import of the other provider can bank-match it.
 */
internal fun bankIdentityFromDedupeKey(dedupeKey: String?): Pair<String, String>? {
    val parts = dedupeKey?.split("|")?.takeIf { it.size == 2 } ?: return null
    val (sortCode, accountNumber) = parts
    return if (sortCode.isNotBlank() && accountNumber.isNotBlank()) sortCode to accountNumber else null
}

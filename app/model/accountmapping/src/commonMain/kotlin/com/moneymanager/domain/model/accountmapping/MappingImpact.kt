package com.moneymanager.domain.model.accountmapping

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.regex.PortableRegex

/**
 * The existing accounts a mapping of [pattern] to [targetAccountId] would take over: those whose name, or
 * one of their earlier names ([previousNames], name to account), the pattern matches as an import tests a
 * value (`containsMatchIn`). An import names a new account after the value it read, so these are the
 * accounts earlier imports created for values the mapping now routes to the target, and re-importing the
 * files that booked onto them moves those rows to the target.
 *
 * A reconciliation source only sees its shadow accounts and an ordinary import never does, so a mapping
 * only reaches accounts on the target's own side of [shadowAccountIds].
 */
fun accountsCapturedByMapping(
    pattern: PortableRegex,
    targetAccountId: AccountId,
    accounts: Collection<Account>,
    previousNames: Map<String, AccountId>,
    shadowAccountIds: Set<AccountId>,
): List<Account> {
    if (pattern.pattern.isEmpty()) return emptyList()
    val targetIsShadow = targetAccountId in shadowAccountIds
    val byPreviousName = previousNames.filterKeys { pattern.containsMatchIn(it) }.values.toSet()
    return accounts
        .filter { account ->
            account.id != targetAccountId &&
                (account.id in shadowAccountIds) == targetIsShadow &&
                (pattern.containsMatchIn(account.name) || account.id in byPreviousName)
        }.sortedBy { it.name.lowercase() }
}

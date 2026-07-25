package com.moneymanager.csvimporter

import com.moneymanager.domain.model.AccountAttribute
import com.moneymanager.domain.model.AccountId

/**
 * Resolves a CSV value to the account whose account-attribute pattern matches it. Built from the
 * account attributes of one type (e.g. `card-last4`): each attribute value is split into
 * whitespace/comma-separated tokens, and every token is compiled to a case-insensitive [Regex].
 * A value matches an account when any of that account's tokens is [Regex.containsMatchIn] it. The
 * match wins only when it is unambiguous — a value claimed by more than one account resolves to null
 * so the row imports normally instead of reconciling to the wrong account. Mirrors the persisted
 * `AccountMapping` router's regex semantics, but the patterns live on the accounts.
 *
 * A last-4 like "7721" is just a trivial regex, so existing `card-last4` attributes keep working; use
 * `\s` for a literal space in a pattern, since spaces separate tokens.
 *
 * Attribute values are free text a user or an importer may have written without regex intent (a raw
 * card descriptor like `crv*revolut**4647*`), so a token that isn't a valid regex is matched
 * literally rather than failing the whole import.
 */
class AttributeAccountMatcher private constructor(
    private val patterns: List<Pair<Regex, AccountId>>,
) {
    /** The single account whose pattern matches [value], or null when none or more than one does. */
    fun match(value: String): AccountId? {
        val accounts = patterns.mapNotNull { (regex, accountId) -> accountId.takeIf { regex.containsMatchIn(value) } }.toSet()
        return accounts.singleOrNull()
    }

    companion object {
        /** Splits an attribute value into its individual pattern tokens (whitespace/comma-separated). */
        private val TOKEN_DELIMITER = Regex("[\\s,]+")

        fun from(attributes: List<AccountAttribute>): AttributeAccountMatcher =
            AttributeAccountMatcher(
                attributes.flatMap { attribute ->
                    attribute.value
                        .split(TOKEN_DELIMITER)
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .map { compileToken(it) to attribute.accountId }
                },
            )

        /** Compiles a token as a regex, falling back to a literal match when it isn't valid regex syntax. */
        private fun compileToken(token: String): Regex =
            runCatching { Regex(token, RegexOption.IGNORE_CASE) }
                .getOrElse { Regex(Regex.escape(token), RegexOption.IGNORE_CASE) }

        /** Builds a registry keyed by attribute-type name from a mixed list of account attributes. */
        fun registry(attributes: List<AccountAttribute>): Map<String, AttributeAccountMatcher> =
            attributes.groupBy { it.attributeType.name }.mapValues { (_, attrs) -> from(attrs) }
    }
}

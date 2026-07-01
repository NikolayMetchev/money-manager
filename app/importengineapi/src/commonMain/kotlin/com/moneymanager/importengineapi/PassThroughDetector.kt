package com.moneymanager.importengineapi

import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.model.passthrough.PassThroughRule

/**
 * The outcome of matching a row's description against the configured pass-through definitions.
 *
 * @property account The matched definition (carries the conduit account name + relationship type).
 * @property merchantName The real merchant extracted from the description (the spend leg's target).
 */
data class PassThroughMatch(
    val account: PassThroughAccount,
    val merchantName: String,
)

/**
 * Detects pass-through (conduit) charges from a transfer's description using user-editable
 * [PassThroughAccount] configuration. Db-free and shared by every importer (CSV/QIF/API) so the
 * detection logic — and therefore the behaviour across import types — lives in exactly one place and
 * the import engine stays agnostic of any specific provider (e.g. Curve).
 */
class PassThroughDetector(
    accounts: List<PassThroughAccount>,
) {
    private val enabled: List<PassThroughAccount> = accounts.filter { it.enabled && it.rules.isNotEmpty() }

    /**
     * All patterns are compiled once, up front, into an immutable map. This keeps [detect] free of any
     * shared mutable state so the same detector instance can be called safely from the API importer's
     * parallel item-preparation coroutines. Invalid patterns map to null and are skipped at match time.
     */
    private val compiled: Map<String, Regex?> =
        enabled
            .asSequence()
            .flatMap { it.rules.asSequence() }
            .flatMap { sequenceOf(it.detectionPattern, it.merchantPattern) }
            .distinct()
            .associateWith { pattern -> runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull() }

    /**
     * Returns the first matching definition + cleaned merchant name for [description], or null when no
     * enabled definition's detection pattern matches. Patterns are matched case-insensitively.
     */
    fun detect(description: String): PassThroughMatch? {
        for (account in enabled) {
            for (rule in account.rules) {
                val detection = compiled[rule.detectionPattern] ?: continue
                if (detection.containsMatchIn(description)) {
                    val merchant = extractMerchant(rule, description, detection)
                    if (merchant.isNotBlank()) {
                        return PassThroughMatch(account, merchant)
                    }
                }
            }
        }
        return null
    }

    private fun extractMerchant(
        rule: PassThroughRule,
        description: String,
        detection: Regex,
    ): String {
        val merchantRegex = compiled[rule.merchantPattern]
        val match = merchantRegex?.find(description)
        if (match != null) {
            return substituteTemplate(rule.merchantTemplate, match).trim()
        }
        // Fallback: strip the detection match (e.g. the "CRV*" prefix) and collapse whitespace.
        return detection.replaceFirst(description, "").trim().replace(MULTISPACE, " ")
    }

    private companion object {
        val MULTISPACE = Regex("\\s+")

        /** Substitutes `$0`, `$1`..`$9` and `${name}` tokens in [template] from [match]'s groups. */
        fun substituteTemplate(
            template: String,
            match: MatchResult,
        ): String =
            TOKEN.replace(template) { token ->
                val named = token.groupValues[1]
                val numbered = token.groupValues[2]
                when {
                    named.isNotEmpty() -> runCatching { match.groups[named]?.value }.getOrNull().orEmpty()
                    numbered.isNotEmpty() -> match.groupValues.getOrNull(numbered.toInt()).orEmpty()
                    else -> ""
                }
            }

        val TOKEN = Regex("\\$\\{([^}]+)}|\\$(\\d)")
    }
}

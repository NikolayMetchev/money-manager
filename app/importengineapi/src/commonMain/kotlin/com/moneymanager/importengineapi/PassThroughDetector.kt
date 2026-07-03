package com.moneymanager.importengineapi

import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.model.passthrough.PassThroughRule

/**
 * One peeled conduit hop of a pass-through chain.
 *
 * @property account The matched definition (carries the conduit account name + relationship type).
 * @property merchantText The description remainder after this hop's prefix was stripped — the next
 * hop's input, and for the last hop the real merchant name.
 */
data class PassThroughHop(
    val account: PassThroughAccount,
    val merchantText: String,
)

/**
 * The outcome of matching a row's description against the configured pass-through definitions.
 *
 * @property hops The matched conduits, outermost first (e.g. `CRV*PAYPAL *X` yields Curve then PayPal).
 * @property merchantName The real merchant extracted from the description (the final spend leg's target).
 */
data class PassThroughMatch(
    val hops: List<PassThroughHop>,
    val merchantName: String,
) {
    /** The matched conduit definitions, outermost first. */
    val accounts: List<PassThroughAccount> get() = hops.map { it.account }
}

/**
 * Detects pass-through (conduit) charges from a transfer's description using user-editable
 * [PassThroughAccount] configuration. Db-free and shared by every importer (CSV/QIF/API) so the
 * detection logic — and therefore the behaviour across import types — lives in exactly one place and
 * the import engine stays agnostic of any specific provider (e.g. Curve).
 *
 * Detection peels conduits iteratively: after a definition strips its prefix, the remaining merchant
 * text is re-run through every definition until none match, so chained conduits
 * (`CRV*PAYPAL *THEPIHUT` → card → Curve → PayPal → merchant) resolve in whatever order they appear.
 */
class PassThroughDetector(
    accounts: List<PassThroughAccount>,
) {
    private val definitions: List<PassThroughAccount> = accounts.filter { it.rules.isNotEmpty() }

    /**
     * All patterns are compiled once, up front, into an immutable map. This keeps [detect] free of any
     * shared mutable state so the same detector instance can be called safely from the API importer's
     * parallel item-preparation coroutines. Invalid patterns map to null and are skipped at match time.
     */
    private val compiled: Map<String, Regex?> =
        definitions
            .asSequence()
            .flatMap { it.rules.asSequence() }
            .flatMap { sequenceOf(it.detectionPattern, it.merchantPattern) }
            .distinct()
            .associateWith { pattern -> runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull() }

    /**
     * Returns the chain of conduits peeled from [description] (outermost first) plus the cleaned
     * merchant name, or null when no definition's detection pattern matches. Patterns are matched
     * case-insensitively. Peeling stops when no definition matches the remainder, when a rule fails to
     * shrink the text (defensive against self-matching rules), or after [MAX_HOPS] hops.
     */
    fun detect(description: String): PassThroughMatch? {
        val hops = mutableListOf<PassThroughHop>()
        var text = description
        var hop = detectOne(text)
        while (hop != null && hop.merchantText != text && hops.size < MAX_HOPS) {
            hops += hop
            text = hop.merchantText
            hop = detectOne(text)
        }
        if (hops.isEmpty()) return null
        return PassThroughMatch(hops, hops.last().merchantText)
    }

    private fun detectOne(description: String): PassThroughHop? {
        for (account in definitions) {
            for (rule in account.rules) {
                val detection = compiled[rule.detectionPattern] ?: continue
                if (detection.containsMatchIn(description)) {
                    val merchant = extractMerchant(rule, description, detection)
                    if (merchant.isNotBlank()) {
                        return PassThroughHop(account, merchant)
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
        /** Upper bound on chain length, purely defensive against pathological rule sets. */
        const val MAX_HOPS = 8

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

        // The escape is NOT redundant (qodana false positive): the JVM regex engine accepts a bare
        // `}`, but Android's ICU engine rejects it with a PatternSyntaxException at class-init.
        @Suppress("RegExpRedundantEscape")
        val TOKEN = Regex("\\$\\{([^}]+)\\}|\\$(\\d)")
    }
}

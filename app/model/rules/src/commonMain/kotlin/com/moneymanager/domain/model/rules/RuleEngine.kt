package com.moneymanager.domain.model.rules

import com.moneymanager.regex.firstMatch

/** A [RuleAction.Attribute] claim, its value already computed. */
data class RuleAttribute(
    val typeName: String,
    val value: String,
    val uniqueIdentifier: Boolean,
)

/** A [RuleAction.Leg] claim. */
data class RuleLeg(
    val group: String,
    val role: LegRole,
)

/**
 * What a strategy's rules decided for one record. Single-valued slots hold the first claim (null when no
 * rule claimed them); list slots hold every claim in rule order.
 *
 * @property record The record as the rules left it: read every value through this, so a rewrite counts.
 * @property flipped Whether an odd number of [RuleAction.Flip]s applied.
 * @property incoming The claimed direction; null leaves it to the source's own default.
 * @property excludeReason The [RuleAction.Exclude] reason, blank when the rule gave none.
 * @property accounts Every account candidate per side, in rule order; the importer takes the first that
 *   applies (see [AccountNaming]).
 * @property rewrites Every value the rules set or rewrote, by path, in the order first written.
 * @property capture The claimed [RuleAction.Capture] target.
 */
data class RuleOutcome(
    val record: Record,
    val skipped: Boolean = false,
    val flipped: Boolean = false,
    val incoming: Boolean? = null,
    val excluded: Boolean = false,
    val excludeReason: String? = null,
    val accounts: Map<AccountSide, List<AccountNaming>> = emptyMap(),
    val externalId: String? = null,
    val counterpartyIsPerson: Boolean = false,
    val ignoreCounterpartyId: Boolean = false,
    val attributes: List<RuleAttribute> = emptyList(),
    val fees: List<FeeRule> = emptyList(),
    val foreignAmount: ForeignAmount? = null,
    val conduitAccountName: String? = null,
    val tradeBuy: Boolean? = null,
    val legs: List<RuleLeg> = emptyList(),
    val ledgerTradeLeg: Boolean = false,
    val rewrites: Map<String, String?> = emptyMap(),
    val capture: CaptureTarget? = null,
) {
    /** The account candidates claimed for [side], in rule order. */
    fun accountsFor(side: AccountSide): List<AccountNaming> = accounts[side].orEmpty()
}

/**
 * A record with some values rewritten by rules. A value set to null reads as absent; every other path
 * reads through to [base].
 */
class RewrittenRecord(
    private val base: Record,
    private val overrides: Map<String, String?>,
) : Record {
    override fun exists(path: String): Boolean = if (path in overrides) overrides[path] != null else base.exists(path)

    override fun text(path: String): String? = if (path in overrides) overrides[path] else base.text(path)

    override fun arrayTexts(path: String): List<String>? = if (path in overrides) null else base.arrayTexts(path)

    override fun objectSize(path: String): Int? = if (path in overrides) null else base.objectSize(path)
}

/**
 * Runs a strategy's [StrategyRule]s over records. Wraps a [RuleEvaluator] (and so its pattern cache):
 * create one per import. Not thread-safe.
 */
class RuleEngine(
    val evaluator: RuleEvaluator = RuleEvaluator(),
) {
    /** What [rules] decide for [record], applied in order. */
    fun evaluate(
        rules: List<StrategyRule>,
        record: Record,
    ): RuleOutcome {
        val state = State(record)
        for (rule in rules) {
            if (!evaluator.all(rule.conditions, state.current)) continue
            for (action in rule.actions) {
                if (!apply(action, state)) break
                if (state.skipped) return state.toOutcome()
            }
        }
        return state.toOutcome()
    }

    /**
     * [value] computed against [record]: blank when it reads nothing. A read value is trimmed before its
     * extraction runs, as a source's own padding is never part of the value.
     */
    fun compute(
        value: RuleValue,
        record: Record,
    ): String =
        when (value) {
            is RuleValue.Read -> evaluator.resolve(value.expr) { record.text(it)?.trim() }.trim()
            is RuleValue.Template -> renderTemplate(value.template, record)
        }

    /** Applies [action]; false when the rule's remaining actions shouldn't run (a capture that didn't match). */
    @Suppress("CyclomaticComplexMethod", "LongMethod") // One branch per action; splitting it would only scatter the table.
    private fun apply(
        action: RuleAction,
        state: State,
    ): Boolean {
        when (action) {
            is RuleAction.SwapValues -> {
                val first = state.current.text(action.first)
                val second = state.current.text(action.second)
                state.rewrite(action.first, second)
                state.rewrite(action.second, first)
            }

            is RuleAction.SetValue -> {
                state.rewrite(action.path, compute(action.value, state.current))
            }

            RuleAction.Flip -> {
                state.flipped = !state.flipped
            }

            is RuleAction.SetDirection -> {
                state.incoming = state.incoming ?: action.incoming
            }

            RuleAction.Skip -> {
                state.skipped = true
            }

            is RuleAction.Exclude -> {
                if (!state.excluded) {
                    state.excluded = true
                    state.excludeReason = action.reason?.let { compute(it, state.current) }.orEmpty()
                }
            }

            is RuleAction.Account -> {
                state.accounts.getOrPut(action.side) { mutableListOf() }.add(action.naming)
            }

            is RuleAction.ExternalId -> {
                state.externalId = state.externalId ?: compute(action.value, state.current).ifBlank { null }
            }

            RuleAction.CounterpartyIsPerson -> {
                state.counterpartyIsPerson = true
            }

            RuleAction.IgnoreCounterpartyId -> {
                state.ignoreCounterpartyId = true
            }

            is RuleAction.Attribute -> {
                val value = compute(action.value, state.current)
                if (value.isNotBlank()) state.attributes += RuleAttribute(action.typeName, value, action.uniqueIdentifier)
            }

            is RuleAction.Fee -> {
                state.fees += action.fee
            }

            is RuleAction.Foreign -> {
                state.foreignAmount = state.foreignAmount ?: action.foreignAmount
            }

            is RuleAction.ThroughConduit -> {
                state.conduitAccountName = state.conduitAccountName ?: action.accountName
            }

            is RuleAction.TradeSide -> {
                state.tradeBuy = state.tradeBuy ?: action.buy
            }

            is RuleAction.Leg -> {
                state.legs += RuleLeg(action.group, action.role)
            }

            RuleAction.LedgerTradeLeg -> {
                state.ledgerTradeLeg = true
            }

            is RuleAction.Capture -> {
                return capture(action, state)
            }
        }
        return true
    }

    private fun capture(
        action: RuleAction.Capture,
        state: State,
    ): Boolean {
        val value = state.current.text(action.path) ?: return false
        val match = evaluator.regex(action.pattern).firstMatch(value) ?: return false
        val groups = match.groups as? MatchNamedGroupCollection
        for (name in captureGroupNames(action.pattern)) {
            // A group the pattern names but this match skipped (an optional part) sets nothing.
            groups?.get(name)?.value?.let { state.rewrite(name, it.trim()) }
        }
        state.capture = state.capture ?: action.target
        return true
    }

    private class State(
        private val base: Record,
    ) {
        private val overrides = LinkedHashMap<String, String?>()
        var current: Record = base
            private set
        var skipped = false
        var flipped = false
        var incoming: Boolean? = null
        var excluded = false
        var excludeReason: String? = null
        val accounts = LinkedHashMap<AccountSide, MutableList<AccountNaming>>()
        var externalId: String? = null
        var counterpartyIsPerson = false
        var ignoreCounterpartyId = false
        val attributes = mutableListOf<RuleAttribute>()
        val fees = mutableListOf<FeeRule>()
        var foreignAmount: ForeignAmount? = null
        var conduitAccountName: String? = null
        var tradeBuy: Boolean? = null
        val legs = mutableListOf<RuleLeg>()
        var ledgerTradeLeg = false
        var capture: CaptureTarget? = null

        fun rewrite(
            path: String,
            value: String?,
        ) {
            overrides[path] = value
            current = RewrittenRecord(base, overrides.toMap())
        }

        fun toOutcome(): RuleOutcome =
            RuleOutcome(
                record = current,
                skipped = skipped,
                flipped = flipped,
                incoming = incoming,
                excluded = excluded,
                excludeReason = excludeReason,
                accounts = accounts.mapValues { it.value.toList() },
                externalId = externalId,
                counterpartyIsPerson = counterpartyIsPerson,
                ignoreCounterpartyId = ignoreCounterpartyId,
                attributes = attributes.toList(),
                fees = fees.toList(),
                foreignAmount = foreignAmount,
                conduitAccountName = conduitAccountName,
                tradeBuy = tradeBuy,
                legs = legs.toList(),
                ledgerTradeLeg = ledgerTradeLeg,
                rewrites = overrides.toMap(),
                capture = capture,
            )
    }
}

// The closing brace is a character class: Android's ICU regex rejects a bare `}`, and Qodana flags `\}` as redundant.
private val TEMPLATE_PLACEHOLDER = Regex("""\{([^{}]+)[}]""")

/** [template] with each `{path}` replaced by [record]'s value there (blank when absent). */
fun renderTemplate(
    template: String,
    record: Record,
): String = TEMPLATE_PLACEHOLDER.replace(template) { record.text(it.groupValues[1]).orEmpty() }

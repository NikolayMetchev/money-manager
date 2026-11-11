package com.moneymanager.reconciliation

import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.model.TradeId
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ManualTradeInput
import com.moneymanager.importengineapi.createManualTradeEntries
import com.moneymanager.importengineapi.createReconciliationMatches
import com.moneymanager.importengineapi.setReconciliationLinks
import com.moneymanager.importengineapi.undoManualTradeEntries
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Fewest decimal places two amounts must keep for their rounding to count as agreeing (Revolut keeps two). */
private const val MIN_COMPARED_DECIMALS = 2

/** Decimal places in a stored amount: the 1e18 scale every asset uses, less the trailing zeros. */
private const val SCALE_DIGITS = 18

/**
 * What a reconciliation source's own record says the unnamed side of a placeholder trade was: [shadowTrade], a trade on one
 * of the source's shadow accounts at the same instant with the same known leg, whose other leg is [otherSide].
 */
data class OtherSideSuggestion(
    val placeholder: Trade,
    val placeholderAssetCode: String,
    val shadowTrade: Trade,
    val otherSide: Money,
)

/**
 * The suggestion for each placeholder in [placeholders] (trade and the code of its unnamed asset) among [shadowTrades]. A
 * placeholder's known leg must be one of a shadow trade's legs: the same asset on the same side, at the same second, and an
 * amount that agrees at the decimals both keep (a statement shows two, the source eight: the longer one cut or rounded to
 * the shorter), on a wallet that [mirrors] the placeholder's account (linked to it, or named for it). Nothing is suggested when two
 * shadow trades fit one placeholder, or one fits two placeholders: that is a question for a person.
 */
fun suggestOtherSides(
    placeholders: List<Pair<Trade, String>>,
    shadowTrades: List<Trade>,
    mirrors: (wallet: AccountId, real: AccountId) -> Boolean = { _, _ -> true },
): Map<TradeId, OtherSideSuggestion> {
    val fits =
        placeholders.associate { (placeholder, code) ->
            val placeholderIsTo = placeholder.to.asset.code == code
            val known = if (placeholderIsTo) placeholder.from else placeholder.to
            placeholder to
                shadowTrades.mapNotNull { shadow ->
                    val sameSide = if (placeholderIsTo) shadow.from else shadow.to
                    val other = if (placeholderIsTo) shadow.to else shadow.from
                    OtherSideSuggestion(placeholder, code, shadow, other).takeIf {
                        shadow.timestamp.epochSeconds == placeholder.timestamp.epochSeconds &&
                            mirrors(shadow.fromAccountId, placeholder.fromAccountId) &&
                            agree(sameSide, known)
                    }
                }
        }
    val claims =
        fits.values
            .flatten()
            .groupingBy { it.shadowTrade.id }
            .eachCount()
    return fits.values
        .mapNotNull { suggestions -> suggestions.singleOrNull()?.takeIf { claims.getValue(it.shadowTrade.id) == 1 } }
        .associateBy { it.placeholder.id }
}

private fun agree(
    a: Money,
    b: Money,
): Boolean {
    val left = Digits.of(a.amount)
    val right = Digits.of(b.amount)
    val decimals = minOf(left.decimalsKept, right.decimalsKept)
    // The longer amount agrees when cut or rounded to the shorter one's decimals.
    val agreeAtSharedDecimals =
        decimals >= MIN_COMPARED_DECIMALS &&
            (left.cut(decimals) == right.cut(decimals) || left.rounded(decimals) == right.rounded(decimals))
    return a.asset.code == b.asset.code && (a.amount == b.amount || agreeAtSharedDecimals)
}

/** A stored amount's whole and fractional digits, for comparing at fewer decimals than it keeps. */
private class Digits(
    private val whole: String,
    private val fraction: String,
) {
    val decimalsKept: Int get() = fraction.trimEnd('0').length

    fun cut(decimals: Int): String = whole + fraction.take(decimals)

    fun rounded(decimals: Int): String {
        val kept = cut(decimals)
        return if (decimals < SCALE_DIGITS && fraction[decimals] >= '5') increment(kept) else kept
    }

    companion object {
        fun of(amount: BigInteger): Digits {
            val digits = amount.abs().toString().padStart(SCALE_DIGITS + 1, '0')
            return Digits(digits.dropLast(SCALE_DIGITS), digits.takeLast(SCALE_DIGITS))
        }

        private fun increment(digits: String): String {
            val result = digits.toCharArray()
            var index = result.lastIndex
            while (index >= 0 && result[index] == '9') {
                result[index] = '0'
                index--
            }
            return if (index < 0) "1" + result.concatToString() else result.also { it[index] = it[index] + 1 }.concatToString()
        }
    }
}

/**
 * Takes [suggestions]: books each as the trade the source describes (a manual entry, so the statement's own record retires
 * it should one turn up) and stores the match between it and the source's trade, so the reconciliation screen counts the
 * two as one movement. [links] (shadow wallet → the real account it mirrors, as [planAutoLinks] works out) are made at the
 * same time, since a person accepting a suggestion has said the wallet is that account.
 */
suspend fun ImportEngine.acceptOtherSideSuggestions(
    suggestions: List<OtherSideSuggestion>,
    links: Map<AccountId, AccountId>,
) {
    if (suggestions.isEmpty()) return
    val created =
        createManualTradeEntries(
            suggestions.map { ManualTradeInput(it.placeholder, it.placeholderAssetCode, it.otherSide) },
        )
    // The matches are a second write: were it to fail, the placeholders would be gone from Manual Entries for nothing, so put them back.
    var matched = false
    try {
        createReconciliationMatches(suggestions.mapIndexed { index, suggestion -> listOf(created[index], suggestion.shadowTrade.id) })
        matched = true
    } finally {
        if (!matched) withContext(NonCancellable) { undoManualTradeEntries(created, suggestions.map { it.placeholder.id }) }
    }
    links.forEach { (wallet, real) -> setReconciliationLinks(wallet, setOf(real)) }
}

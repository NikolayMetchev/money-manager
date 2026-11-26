package com.moneymanager.domain.model.apistrategy

import com.moneymanager.domain.model.serialization.SortedStringListSerializer
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/**
 * How a [ApiDateWindowing] window bound is encoded into its start/end request parameters.
 *
 * [EPOCH_MS] — integer milliseconds since the epoch (Crypto.com).
 * [EPOCH_S] — integer whole seconds since the epoch (Kraken `start`/`end`).
 * [ISO_8601] — an ISO-8601 instant string (Wise, Starling).
 */
@Serializable
enum class WindowBoundFormat {
    EPOCH_MS,
    EPOCH_S,
    ISO_8601,
}

/**
 * Splits an endpoint's history into fixed-length date windows, one request unit per window, each bounded
 * by [startParam]/[endParam] (also exposed to templating as `window.start`/`window.end`). Windows reach
 * back [lookbackDays] and are anchored to fixed [windowDays] boundaries, so earlier windows produce
 * stable, cacheable URLs; only the final window (ending "now") shifts across re-imports. A null
 * [windowDays] sends the requested period as a single unit for APIs with no fixed range limit.
 *
 * @property rangeErrorSubstrings Case-insensitive error-body substrings meaning "this particular window
 *   is outside the range the provider will serve" (Binance `asset/transfer` only answers for the last 6
 *   months; its Simple Earn history caps the span at 30 days). A window failing with one of these is
 *   skipped and the newer ones still run, instead of abandoning the whole endpoint. Never encoded when
 *   left at the default (which covers the Binance phrasings), so it is still applied to configs written
 *   before it existed.
 */
@Serializable
data class ApiDateWindowing(
    val startParam: String = "intervalStart",
    val endParam: String = "intervalEnd",
    val windowDays: Int? = 469,
    val lookbackDays: Int = 365 * 6,
    val boundFormat: WindowBoundFormat = WindowBoundFormat.EPOCH_MS,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @Serializable(with = SortedStringListSerializer::class)
    val rangeErrorSubstrings: List<String> = listOf("range is too large", "time range too large"),
)

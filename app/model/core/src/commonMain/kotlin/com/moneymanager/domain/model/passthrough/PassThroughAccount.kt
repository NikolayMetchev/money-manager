package com.moneymanager.domain.model.passthrough

import com.moneymanager.domain.model.WellKnownIds
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Configuration for a "pass-through" (conduit) account such as Curve: a wrapper card whose charges are
 * forwarded to an underlying funding card, where the underlying card's statement carries the real
 * merchant under a recognisable marker (e.g. a `CRV*` prefix on the description).
 *
 * This is generic, user-editable configuration — the import engine knows nothing about Curve. Each
 * definition names a [conduitAccountName] (the account money passes through, e.g. "Curve") and a set of
 * [rules] that detect a pass-through charge and extract the real merchant from a source row's
 * description. When a row matches, importers route the charge through the conduit
 * (card → conduit → merchant) instead of card → "CRV*…junk merchant".
 *
 * @property id Stable identifier (seeded definitions use fixed ids).
 * @property name Human-readable name (e.g. "Curve"); unique.
 * @property conduitAccountName The account every matched charge passes through; resolved/created on import.
 * @property relationshipTypeId The relationship type linking the funding and spend legs; defaults to the
 *   seeded `pass-through` type.
 * @property rules Ordered detection rules; the first whose [PassThroughRule.detectionPattern] matches wins.
 */
@Serializable
data class PassThroughAccount(
    val id: PassThroughAccountId,
    val name: String,
    val conduitAccountName: String,
    val relationshipTypeId: Long = WellKnownIds.PASS_THROUGH_RELATIONSHIP_TYPE_ID,
    val rules: List<PassThroughRule> = emptyList(),
)

/**
 * One detection rule. [detectionPattern] is matched (case-insensitively) against a row's description to
 * decide whether it is a pass-through charge. When it matches, [merchantPattern] (also case-insensitive)
 * is run against the same description and [merchantTemplate] produces the cleaned merchant name from its
 * capture groups (`$0` whole match, `$1`..`$9` numbered groups). When [merchantPattern] does not match,
 * the description with the [detectionPattern] match stripped is used as a fallback.
 *
 * For Curve, one rule covers both Crypto.com (`Crv*Sainsburys`) and Monzo
 * (`CRV*NATIONAL LOTTERY   London        GBR`): detection `(?i)^CRV\*`, merchant
 * `(?i)^CRV\*\s*(.+?)(?:\s{2,}.*)?$` → `$1`.
 */
@Serializable
data class PassThroughRule(
    val detectionPattern: String,
    val merchantPattern: String,
    val merchantTemplate: String = "$1",
)

/** Stable identifier for a [PassThroughAccount]. */
@Serializable
@JvmInline
value class PassThroughAccountId(
    val value: Long,
) {
    override fun toString() = value.toString()
}

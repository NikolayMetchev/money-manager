package com.moneymanager.domain.model.csvstrategy

import kotlinx.serialization.Serializable

/**
 * Declares how a CSV source expresses an **asset conversion** that arrives as two (or more) separate
 * rows — a *debit* leg (an asset leaving the owner account) plus a *credit* leg (the asset received) —
 * rather than as a single cross-asset row. Such conversions cannot be modelled as one `transfer` (a
 * transfer is single-asset) nor reliably as a `trade` (the source often reports only the aggregate
 * credited amount for a many-assets-in / one-asset-out event, e.g. crypto.com "Convert Dust").
 *
 * When set on a [CsvImportStrategy], the importer:
 * 1. routes every detected leg through a shared **conversion counterparty account** (so both legs
 *    parse as valid single-asset transfers with distinct accounts — the owner account on one side and
 *    the conversion account on the other), and
 * 2. pairs each debit leg to its credit leg and links them with a [relationshipTypeName] relationship,
 *    so the legs read as one conversion event while keeping every owner-account balance exact.
 *
 * This is intentionally source-agnostic: crypto.com is only the first consumer; any exchange export
 * that reports conversions as debited/credited row pairs can supply its own config.
 *
 * @property signalColumn Column examined to classify a row as a conversion leg (e.g. "Transaction Kind").
 * @property debitPattern Regex matched (case-insensitively) against [signalColumn] identifying the
 *                        DEBIT leg — the asset leaving the owner account.
 * @property creditPattern Regex matched (case-insensitively) against [signalColumn] identifying the
 *                         CREDIT leg — the asset received into the owner account.
 * @property conversionAccountName Name of the shared counterparty account the legs route through. Used
 *                                 when a single account fits every conversion. Created on demand.
 * @property conversionAccountRules Optional per-value routing when more than one counterparty account
 *                                  is needed; the first matching rule wins, otherwise
 *                                  [conversionAccountName] is used. At least one of
 *                                  [conversionAccountName]/[conversionAccountRules] must resolve.
 * @property pairingKeyPattern Optional regex whose first capture group on [signalColumn] yields the
 *                             pairing key, so `<base>_debited`/`<base>_credited` collapse to a shared
 *                             `<base>` and only pair within the same family.
 * @property pairingKeyColumns Extra columns whose values must also match for a debit and a credit leg
 *                             to be paired (e.g. a shared reference id). Combined with
 *                             [pairingKeyPattern] to form the full pairing key.
 * @property pairingWindowSeconds Maximum seconds between a debit leg and its credit leg for the two to
 *                                be paired. Tolerates small intra-event timestamp jitter while keeping
 *                                distinct events (typically far apart in time) separate.
 * @property relationshipTypeName Relationship type name linking each debit leg to its credit leg
 *                                (resolved get-or-create, so already-populated databases self-heal).
 */
@Serializable
data class ConversionConfig(
    val signalColumn: String,
    val debitPattern: String,
    val creditPattern: String,
    val conversionAccountName: String? = null,
    // First matching rule wins - order is semantic, keeps default insertion-order serialization.
    val conversionAccountRules: List<ConversionAccountRule> = emptyList(),
    val pairingKeyPattern: String? = null,
    // Column values are joined in order to form the pairing key - order is semantic, keeps default
    // insertion-order serialization.
    val pairingKeyColumns: List<String> = emptyList(),
    val pairingWindowSeconds: Long,
    val relationshipTypeName: String,
) {
    init {
        require(conversionAccountName != null || conversionAccountRules.isNotEmpty()) {
            "ConversionConfig needs a conversionAccountName or at least one conversionAccountRule to route legs through"
        }
    }
}

/**
 * A single per-value routing rule for [ConversionConfig.conversionAccountRules]: when the value in
 * [column] matches [pattern] (case-insensitively), the leg routes through the account named
 * [accountName]. Lets one strategy send different conversion families to different counterparty
 * accounts.
 */
@Serializable
data class ConversionAccountRule(
    val column: String,
    val pattern: String,
    val accountName: String,
)

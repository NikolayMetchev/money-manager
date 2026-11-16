package com.moneymanager.importengineapi

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256

/**
 * The content hash of one import record (`import_record.content_hash`): what decides whether a record must be derived
 * again. It covers the record's staged [values] (a CSV row's cells in column order, an API item's JSON) and
 * [strategyConfigHash], so a record changes when either its source data or the strategy that reads it does.
 *
 * Each value is length-prefixed, so no two different value lists encode alike (`["ab", "c"]` and `["a", "bc"]`).
 * Suspending because the browser's only provider, WebCrypto, has no blocking hash.
 */
suspend fun recordContentHash(
    values: List<String>,
    strategyConfigHash: String,
): String {
    val encoded =
        buildString {
            appendLengthPrefixed(strategyConfigHash)
            append(values.size).append(';')
            values.forEach { appendLengthPrefixed(it) }
        }
    return sha256Hex(encoded)
}

/** The hash of a strategy's config, from its canonical JSON (the encoding strategy sync compares). */
suspend fun strategyConfigHash(canonicalConfigJson: String): String = sha256Hex(canonicalConfigJson)

private fun StringBuilder.appendLengthPrefixed(value: String) {
    append(value.length).append(':').append(value)
}

private suspend fun sha256Hex(text: String): String =
    CryptographyProvider.Default
        .get(SHA256)
        .hasher()
        .hash(text.encodeToByteArray())
        .toHexString()

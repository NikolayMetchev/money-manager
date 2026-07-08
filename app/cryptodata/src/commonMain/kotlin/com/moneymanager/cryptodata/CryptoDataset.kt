package com.moneymanager.cryptodata

import com.moneymanager.domain.model.CryptoRegistry

/** Highest crypto precision we encode (ETH/ERC-20 use 18); bounded so `10^decimals` fits a Long. */
private const val MAX_DECIMALS = 18

/**
 * Parses the bundled/refreshed TSV catalog (`SYMBOL\tNAME\tDECIMALS`, one entry per line, DECIMALS
 * optional) into a ticker→[CryptoRegistry.Entry] map keyed by uppercased symbol. Blank lines and rows
 * with a blank symbol or name are skipped; the first occurrence of a symbol wins (the generator already
 * de-duplicates, so this is just defensive). A missing/blank/invalid decimals field yields a null scale
 * factor ("name known, decimals unknown").
 */
fun parseCryptoDataset(text: String): Map<String, CryptoRegistry.Entry> {
    val result = LinkedHashMap<String, CryptoRegistry.Entry>()
    for (line in text.lineSequence()) {
        val fields = line.split('\t')
        val symbol =
            fields
                .getOrNull(0)
                ?.trim()
                ?.uppercase()
                .orEmpty()
        val name = fields.getOrNull(1)?.trim().orEmpty()
        if (symbol.isNotEmpty() && name.isNotEmpty() && symbol !in result) {
            val decimals =
                fields
                    .getOrNull(2)
                    ?.trim()
                    ?.toIntOrNull()
                    ?.takeIf { it in 0..MAX_DECIMALS }
            result[symbol] = CryptoRegistry.Entry(name, decimals?.let { scaleFactorForDecimals(it) })
        }
    }
    return result
}

/** Serializes [entries] to the TSV form [parseCryptoDataset] reads (sorted by symbol for stable diffs). */
fun renderCryptoDataset(entries: List<CryptoDatasetEntry>): String {
    val deduped = LinkedHashMap<String, CryptoDatasetEntry>()
    for (entry in entries) {
        val symbol = entry.symbol.trim().uppercase()
        val name =
            entry.name
                .trim()
                .replace('\t', ' ')
                .replace('\n', ' ')
        if (symbol.isNotEmpty() && name.isNotEmpty() && symbol !in deduped) {
            deduped[symbol] = entry.copy(symbol = symbol, name = name)
        }
    }
    return deduped.values
        .sortedBy { it.symbol }
        .joinToString("\n", postfix = "\n") { "${it.symbol}\t${it.name}\t${it.decimals ?: ""}" }
}

private fun scaleFactorForDecimals(decimals: Int): Long {
    var factor = 1L
    repeat(decimals.coerceIn(0, MAX_DECIMALS)) { factor *= 10 }
    return factor
}

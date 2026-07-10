package com.moneymanager.cryptodata

import com.moneymanager.domain.model.CryptoRegistry

/**
 * Parses the bundled/refreshed TSV catalog (`SYMBOL\tNAME`, one entry per line; any extra columns
 * are ignored for compatibility with older stored catalogs) into a ticker→[CryptoRegistry.Entry]
 * map keyed by uppercased symbol. Blank lines and rows with a blank symbol or name are skipped; the
 * first occurrence of a symbol wins (the generator already de-duplicates, so this is just defensive).
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
            result[symbol] = CryptoRegistry.Entry(name)
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
        .joinToString("\n", postfix = "\n") { "${it.symbol}\t${it.name}" }
}

package com.moneymanager.cryptodata

import com.moneymanager.domain.model.CryptoRegistry

/**
 * Parses the bundled/refreshed TSV catalog (`SYMBOL\tNAME\tALIAS_OF`, one entry per line, the third column blank
 * unless the symbol is another code for an asset), with symbols and aliases in upper case. Blank lines and rows with
 * a blank symbol or name are skipped; the first occurrence of a symbol wins (the generator already de-duplicates, so
 * this is just defensive).
 */
fun parseCryptoDatasetEntries(text: String): List<CryptoDatasetEntry> {
    val result = LinkedHashMap<String, CryptoDatasetEntry>()
    for (line in text.lineSequence()) {
        val fields = line.split('\t').map { it.trim() }
        val symbol = fields.getOrNull(0).orEmpty().uppercase()
        val name = fields.getOrNull(1).orEmpty()
        if (symbol.isNotEmpty() && name.isNotEmpty() && symbol !in result) {
            result[symbol] = CryptoDatasetEntry(symbol, name, fields.getOrNull(2)?.uppercase()?.ifEmpty { null })
        }
    }
    return result.values.toList()
}

/** The catalog [text] as a ticker→[CryptoRegistry.Entry] map keyed by uppercased symbol. */
fun parseCryptoDataset(text: String): Map<String, CryptoRegistry.Entry> =
    parseCryptoDatasetEntries(text).associate { it.symbol to CryptoRegistry.Entry(it.name) }

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
        .joinToString("\n", postfix = "\n") { "${it.symbol}\t${it.name}\t${it.aliasOf.orEmpty()}" }
}

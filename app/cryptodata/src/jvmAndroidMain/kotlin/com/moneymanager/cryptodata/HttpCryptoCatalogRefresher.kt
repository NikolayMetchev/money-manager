package com.moneymanager.cryptodata

import com.moneymanager.domain.model.CryptoCatalogRefresher
import com.moneymanager.domain.model.CryptoRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** CoinGecko's public coin list — id/symbol/name, no decimals. Attribution/rate limits per their terms. */
private const val COINGECKO_LIST_URL = "https://api.coingecko.com/api/v3/coins/list"

@Serializable
private data class CoinGeckoCoin(
    @SerialName("symbol") val symbol: String = "",
    @SerialName("name") val name: String = "",
)

/**
 * Refreshes the crypto catalog from CoinGecko over the shared Ktor client, persists it to the app data
 * dir, and installs it as overrides on [CryptoRegistry]. Self-contained (owns its HTTP client), so the
 * UI can call it without DI plumbing. Network/parse failures propagate to the caller to surface.
 */
class HttpCryptoCatalogRefresher : CryptoCatalogRefresher {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun refresh(): Int {
        val body =
            HttpClient(CIO).use { client ->
                client.get(COINGECKO_LIST_URL).bodyAsText()
            }
        val entries =
            json
                .decodeFromString<List<CoinGeckoCoin>>(body)
                .map { CryptoDatasetEntry(symbol = it.symbol, name = it.name) }
        val tsv = renderCryptoDataset(entries)
        writeStoredCryptoCatalogText(tsv)
        val parsed = parseCryptoDataset(tsv)
        CryptoRegistry.installOverrides(parsed)
        return parsed.size
    }
}

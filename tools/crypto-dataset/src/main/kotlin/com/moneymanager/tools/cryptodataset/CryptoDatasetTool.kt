package com.moneymanager.tools.cryptodataset

import com.moneymanager.cryptodata.CryptoDatasetEntry
import com.moneymanager.cryptodata.renderCryptoDataset
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

private const val LIST_URL = "https://api.coingecko.com/api/v3/coins/list"
private const val MARKETS_URL = "https://api.coingecko.com/api/v3/coins/markets"
private const val RANKED_PAGES = 4
private const val PER_PAGE = 250
private const val RATE_LIMIT_DELAY_MS = 8_000L

@Serializable
private data class Coin(
    @SerialName("symbol") val symbol: String = "",
    @SerialName("name") val name: String = "",
)

/**
 * Regenerates the bundled `coins.tsv`. Ranked top coins (by market cap) are emitted first so their
 * canonical names win over the noisy long-tail list for colliding tickers (e.g. SOL -> Solana, not a
 * bridged variant). renderCryptoDataset de-dupes by symbol keeping the first (ranked) occurrence.
 */
fun main(args: Array<String>) {
    val outputPath = args.firstOrNull() ?: error("Usage: generateCryptoDataset <output coins.tsv path>")
    val json = Json { ignoreUnknownKeys = true }

    val entries =
        runBlocking {
            HttpClient(CIO).use { client ->
                val ranked = mutableListOf<CryptoDatasetEntry>()
                for (page in 1..RANKED_PAGES) {
                    val body =
                        client
                            .get(MARKETS_URL) {
                                parameter("vs_currency", "usd")
                                parameter("order", "market_cap_desc")
                                parameter("per_page", PER_PAGE)
                                parameter("page", page)
                            }.bodyAsText()
                    json.decodeFromString<List<Coin>>(body).forEach { ranked += CryptoDatasetEntry(it.symbol, it.name) }
                    delay(RATE_LIMIT_DELAY_MS.milliseconds)
                }
                val all =
                    json
                        .decodeFromString<List<Coin>>(client.get(LIST_URL).bodyAsText())
                        .map { CryptoDatasetEntry(it.symbol, it.name) }
                ranked + all
            }
        }

    val tsv = renderCryptoDataset(entries)
    File(outputPath).apply { parentFile?.mkdirs() }.writeText(tsv)
    println("Wrote ${tsv.lineSequence().count { it.isNotBlank() }} entries to $outputPath")
}

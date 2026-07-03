package com.moneymanager.strategycatalog

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.http.appendPathSegments

/**
 * Fetches the central strategy catalog over HTTP: the index.json manifest plus per-artifact JSON
 * files, as published to GitHub Pages by `:tools:strategy-catalog`. Read-only and stateless.
 */
class StrategyCatalogClient(
    private val httpClient: HttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    suspend fun fetchManifest(): CatalogManifest = CatalogManifestCodec.decode(fetch("index.json"))

    suspend fun fetchArtifact(fileName: String): String = fetch(fileName)

    private suspend fun fetch(fileName: String): String =
        httpClient
            .get(baseUrl) {
                // appendPathSegments percent-encodes each segment, so names with spaces
                // ("Monzo CSV.csv.json") resolve correctly.
                url.appendPathSegments(LIBRARY_PATH, fileName)
                expectSuccess = true
            }.body()

    companion object {
        /** The GitHub Pages site the catalog is published to. */
        const val DEFAULT_BASE_URL: String = "https://nikolaymetchev.github.io/money-manager"
        private const val LIBRARY_PATH = "strategy-library"
    }
}

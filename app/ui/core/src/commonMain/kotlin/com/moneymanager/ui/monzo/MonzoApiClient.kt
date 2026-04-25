package com.moneymanager.ui.monzo

interface MonzoApiClient {
    suspend fun get(
        url: String,
        bearerToken: String,
    ): MonzoHttpResponse
}

data class MonzoHttpResponse(
    val statusCode: Int,
    val body: String,
)

expect fun createMonzoApiClient(): MonzoApiClient

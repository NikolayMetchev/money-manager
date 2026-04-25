package com.moneymanager.ui.monzo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

actual fun createMonzoApiClient(): MonzoApiClient = MonzoApiClientJvm()

private class MonzoApiClientJvm : MonzoApiClient {
    override suspend fun get(
        url: String,
        bearerToken: String,
    ): MonzoHttpResponse =
        withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.setRequestProperty("Authorization", "Bearer $bearerToken")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connect()
                val statusCode = connection.responseCode
                val body =
                    if (statusCode < 400) {
                        connection.inputStream.bufferedReader().readText()
                    } else {
                        connection.errorStream?.bufferedReader()?.readText() ?: ""
                    }
                MonzoHttpResponse(statusCode = statusCode, body = body)
            } finally {
                connection.disconnect()
            }
        }
}

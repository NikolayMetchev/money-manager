package com.example.net

import com.example.core.Id
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow

expect fun engine(): io.ktor.client.engine.HttpClientEngineFactory<*>

fun ids(client: HttpClient): Flow<Id> = TODO()

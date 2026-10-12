package com.moneymanager.httpclient

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin

actual val platformHttpEngine: HttpClientEngineFactory<*> = Darwin

package com.moneymanager.httpclient

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.curl.Curl

actual val platformHttpEngine: HttpClientEngineFactory<*> = Curl

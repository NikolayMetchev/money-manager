package com.moneymanager.httpclient

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO

actual val platformHttpEngine: HttpClientEngineFactory<*> = CIO

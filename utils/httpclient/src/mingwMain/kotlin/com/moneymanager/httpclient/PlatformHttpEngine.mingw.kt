package com.moneymanager.httpclient

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.winhttp.WinHttp

actual val platformHttpEngine: HttpClientEngineFactory<*> = WinHttp

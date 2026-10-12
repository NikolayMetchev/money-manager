package com.moneymanager.httpclient

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineFactory

/**
 * The Ktor engine this platform's HTTP clients run on: CIO on JVM and Android, Curl on Linux, Darwin
 * (NSURLSession) on macOS and WinHttp on Windows. The native engines use the platform's own TLS stack and
 * trust store; CIO's TLS on Kotlin/Native is unproven.
 */
expect val platformHttpEngine: HttpClientEngineFactory<*>

/** An [HttpClient] on [platformHttpEngine]. Every production client is built here, never by engine discovery. */
fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit = {}): HttpClient = HttpClient(platformHttpEngine, block)

package com.moneymanager.httpclient

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

/**
 * An [HttpClient] on this platform's default Ktor engine, from `ktor-client-engine-defaults`: OkHttp on JVM and
 * Android, Curl on Linux, Darwin (NSURLSession) on macOS and WinHttp on Windows. The native engines use the
 * platform's own TLS stack and trust store. Every production client is built here, so only this module depends
 * on the engines.
 */
fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit = {}): HttpClient = HttpClient(block)

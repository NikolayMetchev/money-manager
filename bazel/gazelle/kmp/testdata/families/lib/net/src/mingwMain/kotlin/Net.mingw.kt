package com.example.net

import io.ktor.client.engine.winhttp.WinHttp
import kotlinx.coroutines.flow.Flow

actual fun engine() = WinHttp

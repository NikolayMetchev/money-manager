package com.example.net

import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers

actual fun engine() = OkHttp.also { Dispatchers.IO }

package com.example.app

import kotlin.js.Promise
import kotlinx.coroutines.flow.flowOf

actual fun names() = flowOf("js").also { Promise.resolve(1) }

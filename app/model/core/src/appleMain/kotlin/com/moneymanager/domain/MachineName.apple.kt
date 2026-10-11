package com.moneymanager.domain

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.gethostname

@OptIn(ExperimentalForeignApi::class)
internal actual fun machineName(): String? =
    memScoped {
        val buffer = allocArray<ByteVar>(HOST_NAME_BUFFER)
        if (gethostname(buffer, HOST_NAME_BUFFER.toULong()) == 0) buffer.toKString().ifBlank { null } else null
    }

private const val HOST_NAME_BUFFER = 256

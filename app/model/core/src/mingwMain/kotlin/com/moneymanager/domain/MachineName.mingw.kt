package com.moneymanager.domain

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
internal actual fun machineName(): String? = getenv("COMPUTERNAME")?.toKString()?.ifBlank { null }

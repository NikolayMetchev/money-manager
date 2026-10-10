package com.moneymanager.domain.model

import kotlin.jvm.JvmInline

/**
 * Type-safe wrapper for the application version string.
 */
@JvmInline
value class AppVersion(
    val value: String,
)

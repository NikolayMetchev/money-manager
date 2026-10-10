package com.moneymanager.domain.model

import kotlin.jvm.JvmInline

@JvmInline
value class DeviceId(
    val id: Long,
) {
    override fun toString() = id.toString()
}

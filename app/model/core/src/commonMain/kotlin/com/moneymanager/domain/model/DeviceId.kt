package com.moneymanager.domain.model

@JvmInline
value class DeviceId(val id: Long) {
    override fun toString() = id.toString()
}

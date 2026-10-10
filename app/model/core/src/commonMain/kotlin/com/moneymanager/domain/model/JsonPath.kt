package com.moneymanager.domain.model

import kotlin.jvm.JvmInline

@JvmInline
value class JsonPath(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "JSONPath must not be blank" }
    }

    override fun toString(): String = value
}

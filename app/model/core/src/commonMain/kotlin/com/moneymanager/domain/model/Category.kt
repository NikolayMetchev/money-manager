package com.moneymanager.domain.model

data class Category(
    val id: Long = 0,
    val name: String,
    val parentId: Long? = null,
)

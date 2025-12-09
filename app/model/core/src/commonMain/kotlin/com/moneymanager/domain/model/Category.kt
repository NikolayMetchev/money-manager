package com.moneymanager.domain.model

data class Category(
    val id: Long = 0,
    val name: String,
    val parentId: Long? = null,
) {
    companion object {
        const val UNCATEGORIZED_ID = -1L
        const val UNCATEGORIZED_NAME = "Uncategorized"

        val UNCATEGORIZED =
            Category(
                id = UNCATEGORIZED_ID,
                name = UNCATEGORIZED_NAME,
                parentId = null,
            )
    }
}

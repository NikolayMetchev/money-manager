package com.moneymanager.database.mapper

internal fun buildPersonFullName(
    firstName: String?,
    middleName: String?,
    lastName: String?,
): String? =
    listOf(firstName, middleName, lastName)
        .filterNot { it.isNullOrBlank() }
        .joinToString(" ")
        .ifBlank { null }

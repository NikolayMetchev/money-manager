package com.moneymanager.domain.model

import kotlinx.serialization.Serializable

data class Person(
    val id: PersonId,
    val revisionId: Long = 1,
    val firstName: String,
    val middleName: String?,
    val lastName: String?,
) {
    val fullName: String
        get() =
            buildString {
                append(firstName)
                if (!middleName.isNullOrBlank()) {
                    append(" ")
                    append(middleName)
                }
                if (!lastName.isNullOrBlank()) {
                    append(" ")
                    append(lastName)
                }
            }
}

@Serializable
@JvmInline
value class PersonId(val id: Long) {
    override fun toString() = id.toString()
}

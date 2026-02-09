package com.moneymanager.domain.model

data class PersonAccountOwnership(
    val id: Long,
    val revisionId: Long = 1,
    val personId: PersonId,
    val accountId: AccountId,
)

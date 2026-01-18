package com.moneymanager.domain.model

data class PersonAccountOwnership(
    val id: Long,
    val personId: PersonId,
    val accountId: AccountId,
)

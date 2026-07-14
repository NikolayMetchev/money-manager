package com.moneymanager.domain.repository.write

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.repository.PersonAccountOwnershipReadRepository

interface PersonAccountOwnershipWriteRepository : PersonAccountOwnershipReadRepository {
    suspend fun createOwnership(
        personId: PersonId,
        accountId: AccountId,
        source: Source,
    ): Long

    suspend fun deleteOwnership(id: Long)

    suspend fun deleteOwnershipsByPerson(personId: PersonId)

    suspend fun deleteOwnershipsByAccount(accountId: AccountId)
}

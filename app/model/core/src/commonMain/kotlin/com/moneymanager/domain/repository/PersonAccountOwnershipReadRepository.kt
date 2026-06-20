package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.PersonAccountOwnership
import com.moneymanager.domain.model.PersonId
import kotlinx.coroutines.flow.Flow

interface PersonAccountOwnershipReadRepository {
    fun getOwnershipsByPerson(personId: PersonId): Flow<List<PersonAccountOwnership>>

    fun getOwnershipsByAccount(accountId: AccountId): Flow<List<PersonAccountOwnership>>

    fun getAllOwnerships(): Flow<List<PersonAccountOwnership>>

    fun getOwnershipById(id: Long): Flow<PersonAccountOwnership?>
}

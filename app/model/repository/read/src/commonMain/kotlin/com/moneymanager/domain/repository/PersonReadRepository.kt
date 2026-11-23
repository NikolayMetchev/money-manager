package com.moneymanager.domain.repository

import com.moneymanager.domain.model.MergeId
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonAlias
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.PersonMerge
import kotlinx.coroutines.flow.Flow

interface PersonReadRepository {
    fun getAllPeople(): Flow<List<Person>>

    fun getPersonById(id: PersonId): Flow<Person?>

    /** Every alias of every person. */
    fun getAllAliases(): Flow<List<PersonAlias>>

    fun getAliasesByPerson(id: PersonId): Flow<List<PersonAlias>>

    /** Merges that have not been undone, newest first. */
    fun getReversibleMerges(): Flow<List<PersonMerge>>

    fun getMergesForSurvivingPerson(id: PersonId): Flow<List<PersonMerge>>

    fun getMergesForDeletedPerson(id: PersonId): Flow<List<PersonMerge>>

    fun getMerge(id: MergeId): Flow<PersonMerge?>
}

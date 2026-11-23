package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.mapper.PersonMapper
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.MergeId
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonAlias
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.PersonMerge
import com.moneymanager.domain.repository.PersonReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PersonReadRepositoryImpl(
    database: MoneyManagerDatabase,
) : PersonReadRepository {
    private val personSelectQueries = database.personSelectQueries
    private val aliasQueries = database.personAliasSelectQueries
    private val mergeQueries = database.personMergeSelectQueries

    override fun getAllPeople(): Flow<List<Person>> =
        personSelectQueries
            .selectAll()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(PersonMapper::mapList)

    override fun getPersonById(id: PersonId): Flow<Person?> =
        personSelectQueries
            .selectById(id.id)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it?.let(PersonMapper::map) }

    override fun getAllAliases(): Flow<List<PersonAlias>> =
        aliasQueries
            .selectAll()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows -> rows.map { PersonAlias(it.alias, it.name_key, PersonId(it.person_id)) } }

    override fun getAliasesByPerson(id: PersonId): Flow<List<PersonAlias>> =
        aliasQueries
            .selectByPerson(id.id)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows -> rows.map { PersonAlias(it.alias, it.name_key, PersonId(it.person_id)) } }

    override fun getReversibleMerges(): Flow<List<PersonMerge>> =
        mergeQueries.selectReversible(::toMerge).asFlow().mapToList(Dispatchers.Default)

    override fun getMergesForSurvivingPerson(id: PersonId): Flow<List<PersonMerge>> =
        mergeQueries.selectBySurvivingPerson(id.id, ::toMerge).asFlow().mapToList(Dispatchers.Default)

    override fun getMergesForDeletedPerson(id: PersonId): Flow<List<PersonMerge>> =
        mergeQueries.selectByDeletedPerson(id.id, ::toMerge).asFlow().mapToList(Dispatchers.Default)

    override fun getMerge(id: MergeId): Flow<PersonMerge?> =
        mergeQueries.selectById(id.id, ::toMerge).asFlow().mapToOneOrNull(Dispatchers.Default)

    @Suppress("LongParameterList", "UNUSED_PARAMETER")
    private fun toMerge(
        id: Long,
        mergedAt: Long,
        survivingPersonId: Long,
        deletedPersonId: Long,
        firstName: String,
        middleName: String?,
        lastName: String?,
        deletedRevisionId: Long,
        reversed: Long,
    ): PersonMerge =
        PersonMerge(
            id = MergeId(id),
            survivingPersonId = PersonId(survivingPersonId),
            deletedPersonId = PersonId(deletedPersonId),
            deletedName = Person(PersonId(deletedPersonId), firstName = firstName, middleName = middleName, lastName = lastName).fullName,
            reversed = reversed != 0L,
        )
}

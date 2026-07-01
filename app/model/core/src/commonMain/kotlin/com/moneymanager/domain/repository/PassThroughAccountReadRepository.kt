package com.moneymanager.domain.repository

import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.model.passthrough.PassThroughAccountId
import kotlinx.coroutines.flow.Flow

interface PassThroughAccountReadRepository {
    /** All pass-through account definitions, ordered by name. */
    fun getAll(): Flow<List<PassThroughAccount>>

    /** Only the enabled definitions — what importers consult during an import. */
    fun getEnabled(): Flow<List<PassThroughAccount>>

    /** A single definition by id. */
    fun getById(id: PassThroughAccountId): Flow<PassThroughAccount?>
}

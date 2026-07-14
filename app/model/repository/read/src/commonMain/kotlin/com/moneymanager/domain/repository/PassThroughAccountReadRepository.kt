package com.moneymanager.domain.repository

import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.model.passthrough.PassThroughAccountId
import kotlinx.coroutines.flow.Flow

interface PassThroughAccountReadRepository {
    /** All pass-through account definitions, ordered by name — what importers consult during an import. */
    fun getAll(): Flow<List<PassThroughAccount>>

    /** A single definition by id. */
    fun getById(id: PassThroughAccountId): Flow<PassThroughAccount?>
}

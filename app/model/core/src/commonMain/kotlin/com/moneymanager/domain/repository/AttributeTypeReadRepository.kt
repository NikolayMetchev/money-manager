package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.AttributeTypeId
import kotlinx.coroutines.flow.Flow

interface AttributeTypeReadRepository {
    fun getAll(): Flow<List<AttributeType>>

    fun getById(id: AttributeTypeId): Flow<AttributeType?>

    fun getByName(name: String): Flow<AttributeType?>
}

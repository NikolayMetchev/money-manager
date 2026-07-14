package com.moneymanager.domain.repository.write

import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.repository.AttributeTypeReadRepository

interface AttributeTypeWriteRepository : AttributeTypeReadRepository {
    /**
     * Gets or creates an attribute type by name.
     * If an attribute type with the given name already exists, returns its ID.
     * Otherwise, creates a new one and returns its ID.
     *
     * @param name The name of the attribute type
     * @return The AttributeTypeId of the existing or newly created attribute type
     */
    suspend fun getOrCreate(name: String): AttributeTypeId
}

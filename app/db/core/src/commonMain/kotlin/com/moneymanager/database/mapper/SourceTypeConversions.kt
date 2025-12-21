package com.moneymanager.database.mapper

import com.moneymanager.domain.model.SourceType

interface SourceTypeConversions {
    fun toSourceType(name: String): SourceType = SourceType.fromName(name)
}

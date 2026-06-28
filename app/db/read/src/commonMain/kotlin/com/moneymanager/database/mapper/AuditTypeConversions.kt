package com.moneymanager.database.mapper

import com.moneymanager.domain.model.AuditType

interface AuditTypeConversions {
    fun toAuditType(name: String): AuditType = enumValueOf(name.uppercase())
}

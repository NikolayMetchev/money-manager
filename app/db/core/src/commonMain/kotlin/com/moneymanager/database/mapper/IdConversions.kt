package com.moneymanager.database.mapper

import com.moneymanager.domain.model.TransferId

interface IdConversions {
    fun toTransferId(id: Long): TransferId = TransferId(id)
}

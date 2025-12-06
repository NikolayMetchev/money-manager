package com.moneymanager.domain.model

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

interface TransactionId {
    @OptIn(ExperimentalUuidApi::class)
    val id: Uuid
}

package com.moneymanager.database.mapper

import com.moneymanager.domain.model.Account
import tech.mappie.api.ObjectMappie

object AccountMapper :
    ObjectMappie<com.moneymanager.database.sql.account.Account, Account>(),
    IdConversions,
    InstantConversions

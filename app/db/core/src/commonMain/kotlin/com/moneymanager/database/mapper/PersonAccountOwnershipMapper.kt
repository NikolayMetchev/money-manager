package com.moneymanager.database.mapper

import com.moneymanager.database.sql.Person_account_ownership
import com.moneymanager.domain.model.PersonAccountOwnership
import tech.mappie.api.ObjectMappie

object PersonAccountOwnershipMapper :
    ObjectMappie<Person_account_ownership, PersonAccountOwnership>(),
    IdConversions

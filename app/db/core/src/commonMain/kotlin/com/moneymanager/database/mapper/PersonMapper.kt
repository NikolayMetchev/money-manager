package com.moneymanager.database.mapper

import com.moneymanager.domain.model.Person
import tech.mappie.api.ObjectMappie

object PersonMapper :
    ObjectMappie<com.moneymanager.database.sql.Person, Person>(),
    IdConversions

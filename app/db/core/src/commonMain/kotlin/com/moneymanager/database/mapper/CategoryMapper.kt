package com.moneymanager.database.mapper

import com.moneymanager.domain.model.Category
import tech.mappie.api.ObjectMappie

object CategoryMapper : ObjectMappie<com.moneymanager.database.sql.Category, Category>()

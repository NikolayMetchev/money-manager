package com.moneymanager.database.mapper

import com.moneymanager.domain.model.Asset
import tech.mappie.api.ObjectMappie

object AssetMapper : ObjectMappie<com.moneymanager.database.sql.Asset, Asset>()

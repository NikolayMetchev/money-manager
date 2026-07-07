package com.moneymanager.database.mapper

import com.moneymanager.domain.model.CryptoAsset
import tech.mappie.api.ObjectMappie
import com.moneymanager.database.sql.crypto.Crypto as DbCrypto

object CryptoMapper : ObjectMappie<DbCrypto, CryptoAsset>(), IdConversions

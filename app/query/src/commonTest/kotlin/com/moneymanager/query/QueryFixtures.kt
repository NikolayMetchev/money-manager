package com.moneymanager.query

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.CurrencyScaleFactors
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import kotlin.time.Instant

val gbp = Currency(id = CurrencyId(1), code = "GBP", name = "Pound Sterling", scaleFactor = CurrencyScaleFactors.DEFAULT_SCALE_FACTOR)
val usd = Currency(id = CurrencyId(2), code = "USD", name = "US Dollar", scaleFactor = CurrencyScaleFactors.DEFAULT_SCALE_FACTOR)
val btc = CryptoAsset(id = CryptoId(1), code = "BTC", name = "Bitcoin")

val alice = Person(id = PersonId(1), firstName = "Alice", middleName = null, lastName = "Smith")
val bob = Person(id = PersonId(2), firstName = "Bob", middleName = null, lastName = null)

fun account(
    id: Long,
    name: String,
) = Account(id = AccountId(id), name = name, openingDate = Instant.parse("2024-01-02T03:04:05Z"))

fun money(
    amount: String,
    asset: Asset,
) = Money.fromDisplayValue(amount, asset)

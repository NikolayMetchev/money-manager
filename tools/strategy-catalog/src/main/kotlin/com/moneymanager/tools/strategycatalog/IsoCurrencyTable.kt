package com.moneymanager.tools.strategycatalog

import com.moneymanager.domain.model.CurrencyExportEntry
import com.moneymanager.domain.model.CurrencyListExport
import com.moneymanager.domain.strategy.StrategyFileNaming
import java.util.Locale
import java.util.Currency as JavaCurrency

/**
 * The ISO 4217 list as this JDK reports it, the catalog's currency list. Names are English so the list is the same
 * whatever locale the build runs under. A code with no minor unit (gold, SDR, testing codes: -1 to the JDK) has no
 * fraction digits.
 */
internal fun jdkIsoCurrencies(): List<CurrencyExportEntry> =
    JavaCurrency
        .getAvailableCurrencies()
        .map { CurrencyExportEntry(it.currencyCode, it.getDisplayName(Locale.ENGLISH), it.defaultFractionDigits.takeIf { d -> d >= 0 }) }
        .sortedBy { it.code }

/** The catalog's currency-list artifact for [currencies]. */
internal fun currencyListExport(currencies: List<CurrencyExportEntry>): CurrencyListExport =
    CurrencyListExport(name = StrategyFileNaming.CURRENCIES_NAME, currencies = currencies)

package com.moneymanager.tools.strategycatalog

import com.moneymanager.domain.model.CurrencyExportEntry
import com.moneymanager.domain.model.CurrencyListExport
import com.moneymanager.domain.strategy.StrategyFileNaming
import java.util.Locale
import java.util.Currency as JavaCurrency

/**
 * The ISO 4217 list as this JDK reports it, the catalog's currency list. Names are English so the list is the same
 * whatever locale the build runs under. A code with no minor unit (gold, SDR, testing codes: -1 to the JDK) has no
 * fraction digits. Each code carries its [currencyNameAliases].
 */
internal fun jdkIsoCurrencies(): List<CurrencyExportEntry> =
    JavaCurrency
        .getAvailableCurrencies()
        .map {
            CurrencyExportEntry(
                code = it.currencyCode,
                name = it.getDisplayName(Locale.ENGLISH),
                fractionDigits = it.defaultFractionDigits.takeIf { d -> d >= 0 },
                aliases = currencyNameAliases[it.currencyCode].orEmpty(),
            )
        }.sortedBy { it.code }

/**
 * Names statements write a currency as that differ from the JDK's English name. An asset's own name already
 * resolves (Barclaycard's `EURO`, `US DOLLAR`), so only the others are listed: ISO 4217's own wording, and
 * Barclaycard's dotted `U.S. DOLLAR`.
 */
internal val currencyNameAliases: Map<String, Set<String>> =
    mapOf(
        "GBP" to setOf("POUND STERLING"),
        "USD" to setOf("U.S. DOLLAR"),
    )

/** The catalog's currency-list artifact for [currencies]. */
internal fun currencyListExport(currencies: List<CurrencyExportEntry>): CurrencyListExport =
    CurrencyListExport(name = StrategyFileNaming.CURRENCIES_NAME, currencies = currencies)

package com.moneymanager.builtin

import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import kotlin.time.Instant

/** Built-in CSV/QIF import strategy definitions. db-free; the catalog artifacts are exported from these. */
object BuiltInCsvStrategies {
    /**
     * All built-in CSV import strategies. [qifCurrencyId] is the database's GBP for fixed-currency UK statements.
     * The catalog publishes it as "GBP", which an install resolves in its database.
     */
    fun builtInCsvStrategies(
        now: Instant,
        qifCurrencyId: CurrencyId,
    ): List<CsvImportStrategy> =
        listOf(
            SantanderQifStrategies.buildSantanderQifStrategy(now, qifCurrencyId),
            AmexStrategies.buildAmexQifStrategy(now, qifCurrencyId),
            AmexStrategies.buildAmexPdfStrategy(now, qifCurrencyId),
            BarclaycardStrategies.buildBarclaycardOfxStrategy(now),
            BarclaycardStrategies.buildBarclaycardCsvStrategy(now, qifCurrencyId),
            WiseStrategies.buildWiseCsvStrategy(now),
            MonzoStrategies.buildMonzoCsvStrategy(now),
            MarcusStrategies.buildMarcusCsvStrategy(now, qifCurrencyId),
            RevolutStrategies.buildRevolutCsvStrategy(now),
            CryptoComStrategies.buildCryptoComCardStrategy(now),
            CryptoComStrategies.buildCryptoComFiatStrategy(now),
            CryptoComStrategies.buildCryptoComCryptoStrategy(now),
            CurveStrategies.buildCurveCsvStrategy(now),
            CryptoComStrategies.buildCryptoComCardXlsxStrategy(now),
            BinanceStrategies.buildBinanceCsvStrategy(now),
            KoinlyStrategies.buildKoinlyCsvStrategy(now),
            AccointingStrategies.buildAccointingCsvStrategy(now),
            SqliteStrategies.gnucash(now),
            SqliteStrategies.moneyManager(now),
            CelsiusStrategies.buildCelsiusCsvStrategy(now),
            FreetradeStrategies.buildFreetradeCsvStrategy(now),
            BybitStrategies.buildBybitSpotCsvStrategy(now),
            BybitStrategies.buildBybitFundingCsvStrategy(now),
            BybitStrategies.buildBybitUnifiedCsvStrategy(now),
            PayPalStrategies.buildPayPalCsvStrategy(now),
        )
}

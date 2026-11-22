package com.moneymanager.builtin

import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Built-in CSV/QIF import strategy definitions. db-free; the catalog artifacts are exported from these. */
object BuiltInCsvStrategies {
    /** Fixed UUIDs for the built-in CSV/QIF strategies so they can be referenced reliably. */
    val wiseCsvStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000003")
    val monzoCsvStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000004")
    val santanderQifStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000006")
    val cryptoComCardStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000007")
    val cryptoComFiatStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000008")
    val cryptoComCryptoStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000009")
    val curveCsvStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000a")
    val cryptoComCardXlsxStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000b")
    val binanceCsvStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000c")
    val koinlyCsvStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000d")
    val bybitSpotCsvStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000e")
    val bybitFundingCsvStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000f")
    val bybitUnifiedCsvStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000010")
    val payPalCsvStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000011")
    val amexQifStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000014")
    val amexPdfStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000015")
    val accointingCsvStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000016")
    val celsiusCsvStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000017")
    val freetradeCsvStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000018")
    val marcusCsvStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000019")
    val revolutCsvStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000001a")
    val barclaycardOfxStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000001b")
    val barclaycardCsvStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000001c")

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
            CelsiusStrategies.buildCelsiusCsvStrategy(now),
            FreetradeStrategies.buildFreetradeCsvStrategy(now),
            BybitStrategies.buildBybitSpotCsvStrategy(now),
            BybitStrategies.buildBybitFundingCsvStrategy(now),
            BybitStrategies.buildBybitUnifiedCsvStrategy(now),
            PayPalStrategies.buildPayPalCsvStrategy(now),
        )
}

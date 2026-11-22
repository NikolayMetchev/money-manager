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
            buildSantanderQifStrategy(now, qifCurrencyId),
            buildAmexQifStrategy(now, qifCurrencyId),
            buildAmexPdfStrategy(now, qifCurrencyId),
            buildBarclaycardOfxStrategy(now),
            buildBarclaycardCsvStrategy(now, qifCurrencyId),
            buildWiseCsvStrategy(now),
            buildMonzoCsvStrategy(now),
            buildMarcusCsvStrategy(now, qifCurrencyId),
            buildRevolutCsvStrategy(now),
            buildCryptoComCardStrategy(now),
            buildCryptoComFiatStrategy(now),
            buildCryptoComCryptoStrategy(now),
            buildCurveCsvStrategy(now),
            buildCryptoComCardXlsxStrategy(now),
            buildBinanceCsvStrategy(now),
            buildKoinlyCsvStrategy(now),
            buildAccointingCsvStrategy(now),
            buildCelsiusCsvStrategy(now),
            buildFreetradeCsvStrategy(now),
            buildBybitSpotCsvStrategy(now),
            buildBybitFundingCsvStrategy(now),
            buildBybitUnifiedCsvStrategy(now),
            buildPayPalCsvStrategy(now),
        )

    fun buildAccointingCsvStrategy(now: Instant): CsvImportStrategy = AccointingStrategies.buildAccointingCsvStrategy(now)

    fun buildAmexPdfStrategy(
        now: Instant,
        currencyId: CurrencyId,
    ): CsvImportStrategy = AmexStrategies.buildAmexPdfStrategy(now, currencyId)

    fun buildAmexQifStrategy(
        now: Instant,
        currencyId: CurrencyId,
    ): CsvImportStrategy = AmexStrategies.buildAmexQifStrategy(now, currencyId)

    fun buildBarclaycardCsvStrategy(
        now: Instant,
        currencyId: CurrencyId,
    ): CsvImportStrategy = BarclaycardStrategies.buildBarclaycardCsvStrategy(now, currencyId)

    fun buildBarclaycardOfxStrategy(now: Instant): CsvImportStrategy = BarclaycardStrategies.buildBarclaycardOfxStrategy(now)

    fun buildBinanceCsvStrategy(now: Instant): CsvImportStrategy = BinanceStrategies.buildBinanceCsvStrategy(now)

    fun buildBybitFundingCsvStrategy(now: Instant): CsvImportStrategy = BybitStrategies.buildBybitFundingCsvStrategy(now)

    fun buildBybitSpotCsvStrategy(now: Instant): CsvImportStrategy = BybitStrategies.buildBybitSpotCsvStrategy(now)

    fun buildBybitUnifiedCsvStrategy(now: Instant): CsvImportStrategy = BybitStrategies.buildBybitUnifiedCsvStrategy(now)

    fun buildCelsiusCsvStrategy(now: Instant): CsvImportStrategy = CelsiusStrategies.buildCelsiusCsvStrategy(now)

    fun buildCryptoComCardStrategy(now: Instant): CsvImportStrategy = CryptoComStrategies.buildCryptoComCardStrategy(now)

    fun buildCryptoComCardXlsxStrategy(now: Instant): CsvImportStrategy = CryptoComStrategies.buildCryptoComCardXlsxStrategy(now)

    fun buildCryptoComCryptoStrategy(now: Instant): CsvImportStrategy = CryptoComStrategies.buildCryptoComCryptoStrategy(now)

    fun buildCryptoComFiatStrategy(now: Instant): CsvImportStrategy = CryptoComStrategies.buildCryptoComFiatStrategy(now)

    fun buildCurveCsvStrategy(now: Instant): CsvImportStrategy = CurveStrategies.buildCurveCsvStrategy(now)

    fun buildFreetradeCsvStrategy(now: Instant): CsvImportStrategy = FreetradeStrategies.buildFreetradeCsvStrategy(now)

    fun buildKoinlyCsvStrategy(now: Instant): CsvImportStrategy = KoinlyStrategies.buildKoinlyCsvStrategy(now)

    fun buildMarcusCsvStrategy(
        now: Instant,
        currencyId: CurrencyId,
    ): CsvImportStrategy = MarcusStrategies.buildMarcusCsvStrategy(now, currencyId)

    fun buildMonzoCsvStrategy(now: Instant): CsvImportStrategy = MonzoStrategies.buildMonzoCsvStrategy(now)

    fun buildPayPalCsvStrategy(now: Instant): CsvImportStrategy = PayPalStrategies.buildPayPalCsvStrategy(now)

    fun buildRevolutCsvStrategy(now: Instant): CsvImportStrategy = RevolutStrategies.buildRevolutCsvStrategy(now)

    fun buildSantanderQifStrategy(
        now: Instant,
        currencyId: CurrencyId,
    ): CsvImportStrategy = SantanderQifStrategies.buildSantanderQifStrategy(now, currencyId)

    fun buildWiseCsvStrategy(now: Instant): CsvImportStrategy = WiseStrategies.buildWiseCsvStrategy(now)
}

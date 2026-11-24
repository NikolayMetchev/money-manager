package com.moneymanager.builtin

import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Built-in API import strategy definitions. db-free domain objects. */
object BuiltInApiStrategies {
    val monzoStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000001")
    val wiseStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000002")
    val starlingStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000005")
    val cryptoComExchangeStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000009")
    val krakenStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000a")
    val binanceStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000b")
    val coinbaseStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000c")
    val bybitStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000d")
    val payPalStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000e")
    val revolutXStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000f")

    /** All built-in API import strategies. */
    fun builtInApiStrategies(now: Instant): List<ApiImportStrategy> =
        listOf(
            monzo(now),
            wise(now),
            starling(now),
            cryptoComExchange(now),
            kraken(now),
            binance(now),
            coinbase(now),
            bybit(now),
            payPal(now),
            revolutX(now),
        )

    /** The built-in Monzo API import strategy. */
    fun monzo(now: Instant): ApiImportStrategy = MonzoApiStrategy.strategy(now)

    /** The built-in Wise API import strategy. */
    fun wise(now: Instant): ApiImportStrategy = WiseApiStrategy.strategy(now)

    /** The built-in Starling API import strategy. */
    fun starling(now: Instant): ApiImportStrategy = StarlingApiStrategy.strategy(now)

    /** The built-in CryptoComExchange API import strategy. */
    fun cryptoComExchange(now: Instant): ApiImportStrategy = CryptoComExchangeApiStrategy.strategy(now)

    /** The built-in Kraken API import strategy. */
    fun kraken(now: Instant): ApiImportStrategy = KrakenApiStrategy.strategy(now)

    /** The built-in Binance API import strategy. */
    fun binance(now: Instant): ApiImportStrategy = BinanceApiStrategy.strategy(now)

    /** The built-in Coinbase API import strategy. */
    fun coinbase(now: Instant): ApiImportStrategy = CoinbaseApiStrategy.strategy(now)

    /** The built-in Bybit API import strategy. */
    fun bybit(now: Instant): ApiImportStrategy = BybitApiStrategy.strategy(now)

    /** The built-in PayPal API import strategy. */
    fun payPal(now: Instant): ApiImportStrategy = PayPalApiStrategy.strategy(now)

    /** The built-in RevolutX API import strategy. */
    fun revolutX(now: Instant): ApiImportStrategy = RevolutXApiStrategy.strategy(now)
}

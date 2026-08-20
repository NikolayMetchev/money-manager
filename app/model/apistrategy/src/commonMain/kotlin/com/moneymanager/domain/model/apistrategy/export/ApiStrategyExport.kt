package com.moneymanager.domain.model.apistrategy.export

import com.moneymanager.domain.model.apistrategy.ApiStrategyConfig
import kotlinx.serialization.Serializable

/**
 * Portable export format for API import strategies: a [name] plus its [ApiStrategyConfig]. Unlike CSV
 * strategies, an API strategy's configuration holds no database entity references (account/currency/
 * category ids), and credentials live in separate tables — so it is fully portable as-is with no
 * reference resolution needed.
 *
 * @property version App version that created this export (for compatibility tracking)
 * @property name Strategy name (unique)
 */
@Serializable
data class ApiStrategyExport(
    val version: String,
    val name: String,
    val config: ApiStrategyConfig,
)

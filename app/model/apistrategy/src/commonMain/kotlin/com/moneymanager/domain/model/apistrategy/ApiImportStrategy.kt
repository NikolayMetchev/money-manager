package com.moneymanager.domain.model.apistrategy

import com.moneymanager.domain.model.ApiImportStrategyId
import kotlin.time.Instant

/**
 * A reusable, persisted API import strategy: an identity ([id]/[name]) plus the portable
 * [ApiStrategyConfig] describing how to talk to the API and map its responses to accounts,
 * transactions and people.
 *
 * @property config The strategy's full configuration; also the shape persisted as [configJson]
 * @property createdAt Timestamp when this strategy was created
 * @property updatedAt Timestamp when this strategy was last modified
 * @property configJson The raw persisted JSON of [config] (empty for a not-yet-persisted strategy)
 */
data class ApiImportStrategy(
    val id: ApiImportStrategyId,
    val name: String,
    val config: ApiStrategyConfig,
    val createdAt: Instant,
    val updatedAt: Instant,
    val revisionId: Long = 1,
    val configJson: String = "",
)

@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model.apistrategy

import kotlin.time.Instant

/**
 * Represents a reusable API import strategy that defines how to communicate with an external API
 * and map its responses to accounts, transactions, and people.
 *
 * @property id Unique identifier for this strategy
 * @property name Human-readable name for the strategy (must be unique)
 * @property baseUrl Base URL of the API (e.g. "https://api.monzo.com")
 * @property authType Authentication mechanism used by the API
 * @property accountsEndpoint Configuration for the accounts endpoint
 * @property transactionsEndpoint Configuration for the transactions endpoint
 * @property accountMappings JSON field paths for mapping account fields
 * @property transactionMappings JSON field paths for mapping transaction fields
 * @property accountNamePrefix Prefix prepended to account names on import (e.g. "Monzo: ")
 * @property counterpartyPrefix Prefix prepended to counterparty account names (e.g. "Monzo Counterparty: ")
 * @property createdAt Timestamp when this strategy was created
 * @property updatedAt Timestamp when this strategy was last modified
 */
data class ApiImportStrategy(
    val id: ApiImportStrategyId,
    val name: String,
    val baseUrl: String,
    val authType: ApiAuthType,
    val accountsEndpoint: ApiEndpointConfig,
    val transactionsEndpoint: ApiEndpointConfig,
    val accountMappings: ApiAccountMappings,
    val transactionMappings: ApiTransactionMappings,
    val accountNamePrefix: String,
    val counterpartyPrefix: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

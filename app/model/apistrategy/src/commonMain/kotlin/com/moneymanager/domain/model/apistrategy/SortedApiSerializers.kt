package com.moneymanager.domain.model.apistrategy

import com.moneymanager.domain.model.serialization.SortedByListSerializer
import com.moneymanager.domain.model.serialization.SortedListSerializer

/**
 * Serializes query-parameter lists sorted by [ApiQueryParam]'s natural order. Query params are sent
 * as independent key/value pairs — their list position carries no meaning — so this keeps
 * [ApiEndpointConfig.queryParams]/[ApiPaginationConfig.extraParams] byte-stable regardless of the
 * order a UI editor's rows were added in on a given device.
 */
object SortedQueryParamListSerializer : SortedListSerializer<ApiQueryParam>(ApiQueryParam.serializer())

/**
 * Serializes [ApiDataEndpoint] lists (kind, then endpoint path/response key) so
 * [ApiStrategyConfig.dataEndpoints]/[com.moneymanager.domain.model.apistrategy.export.ApiStrategyExport.dataEndpoints]
 * is byte-stable regardless of the order endpoints were added in the editor UI on a given device. Safe
 * because [com.moneymanager.apiimporter] processes each endpoint independently (no first-match /
 * positional-index semantics) — enrichment indexing is built from all endpoints before consumers read it.
 */
object SortedDataEndpointListSerializer : SortedByListSerializer<ApiDataEndpoint>(
    ApiDataEndpoint.serializer(),
    compareBy({ it.kind.name }, { it.endpoint.path }, { it.endpoint.responseArrayKey }),
)

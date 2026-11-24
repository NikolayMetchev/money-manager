package com.moneymanager.builtin

import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiPaginationConfig
import com.moneymanager.domain.model.apistrategy.ApiQueryParam
import com.moneymanager.domain.model.apistrategy.HttpMethodType

/**
 * The endpoint every signed exchange API shares: a path, where its items sit in the response, how it
 * pages, and how its envelope reports success. Each exchange applies its own envelope once, in a local
 * partial application, so the call sites only name what differs per endpoint.
 */
internal fun exchangeEndpoint(
    path: String,
    responseArrayKey: String,
    pagination: ApiPaginationConfig?,
    success: ResponseSuccess? = null,
    method: HttpMethodType = HttpMethodType.GET,
    queryParams: List<ApiQueryParam> = emptyList(),
    requestCostWeight: Int = 1,
    responseObjectValues: Boolean = false,
    itemKeyField: String? = null,
): ApiEndpointConfig =
    ApiEndpointConfig(
        path = path,
        responseArrayKey = responseArrayKey,
        queryParams = queryParams,
        pagination = pagination,
        method = method,
        successCodeField = (success as? ResponseSuccess.CodeField)?.field,
        successCodeOkValue = (success as? ResponseSuccess.CodeField)?.okValue,
        errorArrayField = (success as? ResponseSuccess.ErrorArray)?.field,
        responseObjectValues = responseObjectValues,
        itemKeyField = itemKeyField,
        requestCostWeight = requestCostWeight,
    )

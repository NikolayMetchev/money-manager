package com.moneymanager.importer

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import kotlinx.coroutines.flow.first

/** The `fee-account-for` account attribute: names the account a fee account collects fees for. */
internal val FEE_ACCOUNT_FOR_TYPE = AttributeTypeId(WellKnownIds.ACCOUNT_FEE_ACCOUNT_FOR_ATTR_TYPE_ID)

/** The `reconciliation-source` account attribute: marks a shadow account, its value the source's name. */
internal val RECONCILIATION_SOURCE_TYPE = AttributeTypeId(WellKnownIds.ACCOUNT_RECONCILIATION_SOURCE_ATTR_TYPE_ID)

/** Every shadow account, with the name of the reconciliation source it belongs to. */
internal suspend fun AccountAttributeReadRepository.shadowSources(): Map<AccountId, String> =
    getByType(RECONCILIATION_SOURCE_TYPE).first().associate { it.accountId to it.value }

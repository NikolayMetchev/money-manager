package com.moneymanager.domain.model

data class TransferAttribute(
    val id: Long,
    val transactionId: TransferId,
    val attributeType: AttributeType,
    val value: String,
    val groupKey: String = "",
)

/**
 * An attribute value to be created (before it has an ID).
 * Used when creating new attributes for a transfer, an account or a person.
 *
 * [groupKey] ties this attribute to the others in the same logical tuple on the same entity — a sort code
 * and an account number sharing a group key are one bank identity, and an account may hold several. `""`
 * (the default) means ungrouped, which is what every scalar attribute uses. The key is opaque: never parse
 * it, derive meaning from the values inside a group. See `account_attribute.group_key` for the full
 * contract and `ImportKeys.personalCounterpartyKey` for the natural key writers seed it with.
 */
data class NewAttribute(
    val typeId: AttributeTypeId,
    val value: String,
    val groupKey: String = "",
)

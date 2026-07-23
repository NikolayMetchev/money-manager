package com.moneymanager.domain.model

/**
 * Stable IDs for the well-known attribute and relationship types seeded into a fresh database.
 * Importers reference these by id without a DB lookup. Defined here (db-free) so the import modules
 * can use them without depending on the database layer; the database seeds exactly these ids.
 */
object WellKnownIds {
    /** The SYSTEM device, seeded with this fixed id so seed provenance device_id is a known constant. */
    const val SYSTEM_DEVICE_ID: Long = 1

    /** "excluded" attribute type. Excluded transactions are hidden from balances. */
    const val EXCLUDED_ATTR_TYPE_ID: Long = -1

    /** "account-external-id" attribute type (e.g. counterparty.id value). */
    const val ACCOUNT_EXTERNAL_ID_ATTR_TYPE_ID: Long = -2

    /** "built-in type" attribute type used by built-in counterparty accounts. */
    const val BUILT_IN_COUNTERPARTY_TYPE_ATTR_TYPE_ID: Long = -3

    /** "sort code" account attribute type used for personal counterparties. */
    const val ACCOUNT_SORT_CODE_ATTR_TYPE_ID: Long = -5

    /** "account number" account attribute type used for personal counterparties. */
    const val ACCOUNT_ACCOUNT_NUMBER_ATTR_TYPE_ID: Long = -6

    /**
     * "counterparty-name-key" account attribute: the normalised name key used to reconcile counterparties
     * that have no stable id and no bank identity (e.g. Monzo transfers carrying only an ephemeral
     * anonuser id) onto one account across separate imports.
     */
    const val ACCOUNT_COUNTERPARTY_NAME_KEY_ATTR_TYPE_ID: Long = -7

    /**
     * "card-last4" account attribute: one or more whitespace/comma-separated regex tokens (typically the
     * last-4 digits) identifying the cards funded from this account. It is the default attribute type for
     * the generic attribute-account matcher: a CSV strategy with a
     * `com.moneymanager.domain.model.csvstrategy.CsvImportStrategy.fundingAttributeMatch` (or an
     * `com.moneymanager.domain.model.csvstrategy.AttributeMatchAccountMapping` field) matches a column
     * value against these tokens to route a conduit (e.g. Curve) spend to the funding account. Nothing in
     * code special-cases this id — it is just the built-in Curve strategy's chosen attribute type.
     */
    const val ACCOUNT_CARD_LAST4_ATTR_TYPE_ID: Long = -8

    /** Name of the [ACCOUNT_CARD_LAST4_ATTR_TYPE_ID] attribute type. */
    const val ACCOUNT_CARD_LAST4_ATTR_TYPE_NAME: String = "card-last4"

    /** "reconciled" relationship type: id1 mirrors id2 seen from another source. */
    const val RECONCILED_RELATIONSHIP_TYPE_ID: Long = 1

    /** "fee" relationship type: id1 is the main transaction, id2 its fee transfer. */
    const val FEE_RELATIONSHIP_TYPE_ID: Long = 2

    /**
     * "pass-through" relationship type: id1 is the funding leg (card → conduit), id2 the spend leg
     * (conduit → merchant) of a charge routed through a conduit account (e.g. Curve). See
     * `com.moneymanager.domain.model.passthrough.PassThroughAccount`.
     */
    const val PASS_THROUGH_RELATIONSHIP_TYPE_ID: Long = 3

    /**
     * "reversal" relationship type (seeded id 4): id1 is the reversing movement (a refund/cancellation)
     * and id2 the earlier movement it reverses — for pass-through rows, the two spend legs on the
     * merchant account. The engine resolves it by name (get-or-create), so databases seeded before the
     * type existed self-heal on first use.
     */
    const val REVERSAL_RELATIONSHIP_TYPE_NAME: String = "reversal"
}

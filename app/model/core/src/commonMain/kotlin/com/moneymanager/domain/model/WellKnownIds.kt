package com.moneymanager.domain.model

/**
 * Stable IDs for the well-known attribute and relationship types seeded into a fresh database.
 * Importers reference these by id without a DB lookup. Defined here (db-free) so the import modules
 * can use them without depending on the database layer; the database seeds exactly these ids.
 */
object WellKnownIds {
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

    /** "reconciled" relationship type: id1 mirrors id2 seen from another source. */
    const val RECONCILED_RELATIONSHIP_TYPE_ID: Long = 1

    /** "fee" relationship type: id1 is the main transaction, id2 its fee transfer. */
    const val FEE_RELATIONSHIP_TYPE_ID: Long = 2
}

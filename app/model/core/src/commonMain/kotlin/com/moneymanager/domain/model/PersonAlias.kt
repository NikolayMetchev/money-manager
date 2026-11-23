package com.moneymanager.domain.model

/**
 * Another name a person is known by. [nameKey] is [personNameKey] of [alias], unique across people, so an import
 * that meets the name finds [personId] instead of creating a duplicate.
 */
data class PersonAlias(
    val alias: String,
    val nameKey: String,
    val personId: PersonId,
)

/** Trim, collapse inner whitespace, lower-case: the key a person's name is matched on. */
fun personNameKey(name: String): String = name.trim().replace(Regex("\\s+"), " ").lowercase()

/** A reversible record of a person merge: [deletedPersonId] (named [deletedName]) was merged into [survivingPersonId]. */
data class PersonMerge(
    val id: MergeId,
    val survivingPersonId: PersonId,
    val deletedPersonId: PersonId,
    val deletedName: String,
    val reversed: Boolean = false,
)

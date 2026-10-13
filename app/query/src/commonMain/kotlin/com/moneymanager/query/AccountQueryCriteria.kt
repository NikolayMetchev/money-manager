package com.moneymanager.query

import com.moneymanager.domain.model.AssetId
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId

/**
 * Which accounts (and which of their balances) an [AccountSnapshot.filter] keeps. Every filter left empty
 * matches everything; set ones are combined with AND, and the values within one filter with OR.
 *
 * @property owners keep accounts owned by any of these people.
 * @property name keep accounts whose name matches.
 * @property assets keep accounts holding any of these assets, and list only those assets' balances.
 * @property includeShadow also keep a reconciliation source's shadow accounts (e.g. Koinly's copy), which
 *   hold no real money and are hidden by default.
 * @property nonZeroOnly drop zero balances, and then accounts left with no balance.
 */
data class AccountQueryCriteria(
    val owners: Set<OwnerRef> = emptySet(),
    val name: NameMatch? = null,
    val assets: Set<AssetRef> = emptySet(),
    val includeShadow: Boolean = false,
    val nonZeroOnly: Boolean = false,
)

/** A person named by id, or by full name (matched case-insensitively, and it must name exactly one person). */
sealed interface OwnerRef {
    data class Id(
        val id: PersonId,
    ) : OwnerRef

    data class Name(
        val name: String,
    ) : OwnerRef
}

/** An asset named by id, or by code (case-insensitive; a code shared by several assets matches them all). */
sealed interface AssetRef {
    data class Id(
        val id: AssetId,
    ) : AssetRef

    data class Code(
        val code: String,
    ) : AssetRef
}

/** How an account's name is matched. Both forms ignore case and match anywhere in the name. */
sealed interface NameMatch {
    fun matches(name: String): Boolean

    /** Plain text, as typed into a search box: regex metacharacters are literal. */
    data class Substring(
        val text: String,
    ) : NameMatch {
        override fun matches(name: String): Boolean = name.contains(text, ignoreCase = true)
    }

    /** A regular expression, compiled up front so an invalid one fails when the criteria are built. */
    class Pattern(
        val pattern: String,
    ) : NameMatch {
        private val regex = Regex(pattern, RegexOption.IGNORE_CASE)

        override fun matches(name: String): Boolean = regex.containsMatchIn(name)

        override fun equals(other: Any?): Boolean = other is Pattern && other.pattern == pattern

        override fun hashCode(): Int = pattern.hashCode()

        override fun toString(): String = "Pattern($pattern)"
    }
}

/** An [OwnerRef.Name] that names nobody. */
class UnknownOwnerException(
    val name: String,
) : IllegalArgumentException("No person is named '$name'")

/** An [OwnerRef.Name] that names several people; [candidates] lists them so the caller can ask for an id. */
class AmbiguousOwnerException(
    val name: String,
    val candidates: List<Person>,
) : IllegalArgumentException(
        "Several people are named '$name': ${candidates.joinToString { "${it.fullName} (id ${it.id})" }}",
    )

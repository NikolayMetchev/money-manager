package com.moneymanager.csvimporter

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.importengineapi.AccountAttributeEdit
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.getOrCreateAttributeTypes
import com.moneymanager.importengineapi.setAccountAttributeValues
import kotlinx.coroutines.flow.first

/**
 * The attribute values that make each assigned account the owner of its reference, and [affected]: every observed
 * reference whose owner they change, the assigned ones included. A token can match more than its own card (`7721`
 * is in `CARD-7721` too), so moving it can move other cards with it, and their files need re-importing as well.
 */
class FundingReferenceMoves internal constructor(
    internal val values: List<PlannedAttributeValue>,
    val affected: List<FundingReference>,
)

/** One account attribute's value once the moves are applied, and the attribute it rewrites, if any. */
internal data class PlannedAttributeValue(
    val accountId: AccountId,
    val attributeTypeName: String,
    val groupKey: String,
    val value: String,
    val existing: Pair<Long, AttributeTypeId>?,
)

/**
 * Plans [assignments] without writing anything: each value joins its account's ungrouped attribute of the
 * reference's type and, when another account held it as a token, leaves that one; several cards moving onto or off
 * one account all land in its one attribute value. The values are then matched as they will be, and a move whose
 * card would not end up owned by its account alone (a pattern on the old owner still matches it, say) is refused,
 * as are moves of a card only a pattern matches. [observed] are the references the imports carry, so the plan can
 * name every one whose owner changes. An assignment to the reference's current owner changes nothing.
 */
suspend fun planFundingReferenceMoves(
    assignments: Map<FundingReference, AccountId>,
    observed: List<FundingReference>,
    accountAttributeRepository: AccountAttributeReadRepository,
): FundingReferenceMoves {
    val moves = assignments.filter { (reference, accountId) -> reference.owner != accountId }
    for (reference in moves.keys) {
        require(reference.owner == null || reference.ownedByToken) {
            "${reference.value} is matched by a pattern on its account; change it in the account editor"
        }
    }
    if (moves.isEmpty()) return FundingReferenceMoves(emptyList(), emptyList())
    val typeNames = moves.keys.mapTo(mutableSetOf()) { it.attributeTypeName }

    data class Slot(
        val accountId: AccountId,
        val typeName: String,
        val groupKey: String,
    )

    val attributes = accountAttributeRepository.getAll().first().filter { it.attributeType.name in typeNames }
    val original = attributes.associate { Slot(it.accountId, it.attributeType.name, it.groupKey) to it }
    val current = original.mapValuesTo(linkedMapOf()) { (_, attribute) -> attribute.value }
    for ((reference, accountId) in moves) {
        reference.owner?.let { owner ->
            for (slot in current.keys.filter { it.accountId == owner && it.typeName == reference.attributeTypeName }) {
                current[slot] = removeAttributeToken(current[slot], reference.value)
            }
        }
        val slot = Slot(accountId, reference.attributeTypeName, "")
        current[slot] = addAttributeToken(current[slot], reference.value)
    }

    val before =
        typeNames.associateWith { name ->
            AttributeAccountMatcher.of(
                original.filterKeys { it.typeName == name }.map { (slot, a) ->
                    slot.accountId to
                        a.value
                },
            )
        }
    val after =
        typeNames.associateWith { name ->
            AttributeAccountMatcher.of(
                current.filterKeys { it.typeName == name }.map { (slot, value) ->
                    slot.accountId to
                        value
                },
            )
        }
    for ((reference, accountId) in moves) {
        require(after.getValue(reference.attributeTypeName).match(reference.value) == accountId) {
            "${reference.value} would still be matched by a pattern on another account; change it in the account editor"
        }
    }
    val affected =
        (moves.keys + observed.filter { it.attributeTypeName in typeNames })
            .distinctBy { it.attributeTypeName to it.value }
            .filter { reference ->
                before.getValue(reference.attributeTypeName).match(reference.value) !=
                    after.getValue(reference.attributeTypeName).match(reference.value)
            }
    val values =
        current
            .filter { (slot, value) -> value != original[slot]?.value }
            .map { (slot, value) ->
                val existing = original[slot]
                PlannedAttributeValue(slot.accountId, slot.typeName, slot.groupKey, value, existing?.let { it.id to it.attributeType.id })
            }
    return FundingReferenceMoves(values, affected)
}

/**
 * Writes [moves] in one batch, so a card moving between accounts is never owned by both or by neither. The files of
 * [FundingReferenceMoves.affected] still need re-importing for their rows to follow.
 */
suspend fun ImportEngine.applyFundingReferenceMoves(moves: FundingReferenceMoves) {
    if (moves.values.isEmpty()) return
    val typeIds =
        getOrCreateAttributeTypes(
            moves.values
                .filter { it.existing == null }
                .map { it.attributeTypeName }
                .distinct(),
        )
    setAccountAttributeValues(
        moves.values.map { planned ->
            val typeId =
                planned.existing?.second
                    ?: requireNotNull(typeIds[planned.attributeTypeName]) { "${planned.attributeTypeName} was not resolved" }
            AccountAttributeEdit(planned.accountId, typeId, planned.value, planned.existing?.first, planned.groupKey)
        },
    )
}

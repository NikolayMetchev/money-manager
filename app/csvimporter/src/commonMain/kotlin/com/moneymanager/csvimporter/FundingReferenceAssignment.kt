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
 * Makes each assignment's account the owner of its reference, all in one write: the value joins the account's
 * ungrouped attribute of the reference's type and, when another account held it as a token, leaves that one.
 * Several cards moving onto or off one account all land in its one attribute value. An assignment to the
 * reference's current owner changes nothing. The files that reference them still need re-importing for their
 * rows to follow.
 */
suspend fun ImportEngine.assignFundingReferences(
    assignments: Map<FundingReference, AccountId>,
    accountAttributeRepository: AccountAttributeReadRepository,
) {
    val moves = assignments.filter { (reference, accountId) -> reference.owner != accountId }
    for (reference in moves.keys) {
        require(reference.owner == null || reference.ownedByToken) {
            "${reference.value} is matched by a pattern on its account; change it in the account editor"
        }
    }
    if (moves.isEmpty()) return
    val typeIds = getOrCreateAttributeTypes(moves.keys.map { it.attributeTypeName }.distinct())

    data class Slot(
        val accountId: AccountId,
        val typeId: AttributeTypeId,
        val groupKey: String,
    )

    // What each touched attribute held before (its id and value) and what it holds once every move is applied.
    val original = mutableMapOf<Slot, Pair<Long, String>>()
    val current = mutableMapOf<Slot, String>()
    val loaded = mutableSetOf<AccountId>()

    suspend fun slotsOf(
        accountId: AccountId,
        typeId: AttributeTypeId,
    ): List<Slot> {
        if (loaded.add(accountId)) {
            for (attribute in accountAttributeRepository.getByAccount(accountId).first()) {
                val slot = Slot(accountId, attribute.attributeType.id, attribute.groupKey)
                original[slot] = attribute.id to attribute.value
                current[slot] = attribute.value
            }
        }
        return current.keys.filter { it.accountId == accountId && it.typeId == typeId }
    }

    for ((reference, accountId) in moves) {
        val typeId = requireNotNull(typeIds[reference.attributeTypeName]) { "${reference.attributeTypeName} was not resolved" }
        reference.owner?.let { owner ->
            for (slot in slotsOf(owner, typeId)) current[slot] = removeAttributeToken(current[slot], reference.value)
        }
        slotsOf(accountId, typeId)
        val slot = Slot(accountId, typeId, "")
        current[slot] = addAttributeToken(current[slot], reference.value)
    }
    setAccountAttributeValues(
        current
            .filter { (slot, value) -> value != original[slot]?.second }
            .map { (slot, value) -> AccountAttributeEdit(slot.accountId, slot.typeId, value, original[slot]?.first, slot.groupKey) },
    )
}

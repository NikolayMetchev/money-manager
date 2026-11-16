package com.moneymanager.observationmatcher

import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.observation.ObservationContent
import com.moneymanager.domain.model.observation.ObservationId
import com.moneymanager.domain.model.observation.ObservationOwner

/** An observation by what makes it unique, `UNIQUE(owner, slot)`: a new one has no id yet. */
data class ObservationRef(
    val owner: ObservationOwner,
    val slot: String,
)

/**
 * The identity a source gives one of its records: a CSV strategy's unique-identifier columns ([uniqueKey]) and an API
 * provider's own transaction id ([apiId]). Each owner's slots share its record's identity.
 */
data class RecordIdentity(
    val uniqueKey: Map<String, String> = emptyMap(),
    val apiId: String? = null,
)

/**
 * One observation as the matcher sees it.
 *
 * @property storedId its id, when it is stored; pins and splits name observations by it.
 * @property current the transaction it is assigned to now, or null when it is new.
 * @property family the strategy whose records it observes: the same-family rules only pair observations of one family.
 * @property container the staged file or API session its record belongs to. Two rows of one file are two movements
 *   unless the source itself says they are one (a repeated unique key or API id).
 * @property recordKey an order-free key of its record, such as the record's content hash: ties between equally good
 *   pairs are broken by it. It must not come from database ids or import order (a staged file's id is both), or the
 *   assignment would depend on the order files were imported in.
 */
data class MatchObservation(
    val ref: ObservationRef,
    val storedId: ObservationId?,
    val current: TransactionId?,
    val family: String,
    val container: String,
    val recordKey: String,
    val content: ObservationContent,
    val identity: RecordIdentity = RecordIdentity(),
) {
    /** Whether its source excluded it from balances (a declined payment, a row the source marks deleted). */
    val excluded: Boolean get() = content.attributes.any { it.typeId.id == WellKnownIds.EXCLUDED_ATTR_TYPE_ID }
}

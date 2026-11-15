package com.moneymanager.domain.model.observation

import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.TransactionId

/** A transfer field a person can set by hand; [column] is the `transfer` column it overrides. */
enum class TransferField(
    val column: String,
) {
    TIMESTAMP("timestamp"),
    DESCRIPTION("description"),
    SOURCE_ACCOUNT("source_account_id"),
    TARGET_ACCOUNT("target_account_id"),
    ASSET("asset_id"),
    AMOUNT("amount"),
    ;

    companion object {
        fun fromColumn(column: String): TransferField = entries.first { it.column == column }
    }
}

/** What a person decided about one attribute of a transaction. Trades have no group key: theirs is `""`. */
sealed interface AttributeOverride {
    val typeId: AttributeTypeId
    val groupKey: String

    /** The attribute has [value], whatever imports report. */
    data class Set(
        override val typeId: AttributeTypeId,
        override val groupKey: String,
        val value: String,
    ) : AttributeOverride

    /** The attribute is absent, whatever imports report. */
    data class Removed(
        override val typeId: AttributeTypeId,
        override val groupKey: String,
    ) : AttributeOverride
}

/** A person fixed [observationId] to [transactionId]. */
data class ObservationPin(
    val observationId: ObservationId,
    val transactionId: TransactionId,
)

/** A person said two observations are different movements. [first] has the lower id. */
data class ObservationSplit(
    val first: ObservationId,
    val second: ObservationId,
) {
    init {
        require(first.id < second.id) { "a split names the lower observation id first" }
    }

    companion object {
        fun of(
            a: ObservationId,
            b: ObservationId,
        ): ObservationSplit = if (a.id < b.id) ObservationSplit(a, b) else ObservationSplit(b, a)
    }
}

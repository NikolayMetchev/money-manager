package com.moneymanager.apiimporter

import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.rules.AccountNaming
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.FeeRule
import com.moneymanager.domain.model.rules.NamedAccount
import com.moneymanager.domain.model.rules.Record
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleEngine
import com.moneymanager.domain.model.rules.RuleEvaluator
import com.moneymanager.domain.model.rules.RuleOutcome
import com.moneymanager.domain.model.rules.StrategyRule
import com.moneymanager.domain.model.rules.ValueExpr
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.getOrCreateAttributeType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun JsonObject.resolveJsonObjectPath(dotPath: String): JsonObject? {
    // A blank path means "this object" — used by providers whose counterparty fields are flat on the
    // transaction item rather than nested under a sub-object.
    if (dotPath.isBlank()) return this
    var current: JsonElement = this
    for (part in dotPath.split(".")) {
        current = (current as? JsonObject)?.get(part) ?: return null
    }
    return current as? JsonObject
}

/** Every attribute action in these rules, in order. */
internal fun List<StrategyRule>.attributeActions(): List<RuleAction.Attribute> =
    flatMap {
        it.actions
    }.filterIsInstance<RuleAction.Attribute>()

/** What the first of the rules' [side] candidates that applies to the record names, with that candidate. */
internal fun RuleOutcome.namedAccountWithNaming(
    side: AccountSide,
    engine: RuleEngine,
): Pair<NamedAccount, AccountNaming>? =
    accountsFor(side).firstNotNullOfOrNull { naming -> engine.evaluator.name(naming) { record.text(it) }?.let { it to naming } }

/** What the first of the rules' own-account candidates that applies to the record names. */
internal fun RuleOutcome.ownAccount(engine: RuleEngine): NamedAccount? = namedAccountWithNaming(AccountSide.OWN, engine)?.first

/** Whether [condition] holds for this item (see [JsonRecord]). */
internal fun JsonObject.matches(condition: Condition): Boolean = RuleEvaluator().matches(condition, JsonRecord(this))

/** The value [expr] reads from this item: its first non-blank path, cleaned through its extraction. */
internal fun JsonObject.resolve(expr: ValueExpr): String = RuleEvaluator().resolve(expr) { resolveJsonPath(it) }

/**
 * Whether [item] carries [fee] at all: its conditions hold and, when the fee is charged on one asset,
 * [item] is in [itemAsset]. Shared by the bank-feed and exchange pipelines.
 */
internal fun RuleEvaluator.carriesFee(
    fee: FeeRule,
    item: Record,
    itemAsset: String?,
): Boolean =
    all(fee.conditions, item) &&
        fee.chargedOnAsset?.let { itemAsset != null && resolve(it) { path -> item.text(path) }.equals(itemAsset, ignoreCase = true) } !=
        false

internal fun JsonObject.carriesFee(
    fee: FeeRule,
    itemAsset: String?,
): Boolean = RuleEvaluator().carriesFee(fee, JsonRecord(this), itemAsset)

/**
 * An API item as a rule [Record]: paths are dot-paths (with `[n]` array indexing for scalars). A field
 * holding JSON `null` does not exist (Monzo sends `atm_fees_detailed: null` on every non-ATM
 * transaction), and a blank path addresses the item itself.
 */
internal class JsonRecord(
    private val obj: JsonObject,
) : Record {
    override fun exists(path: String): Boolean = obj.resolveJsonPathElement(path).let { it != null && it != JsonNull }

    override fun text(path: String): String? = obj.resolveJsonPath(path)

    override fun arrayTexts(path: String): List<String>? =
        (obj.resolveJsonPathElement(path) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    override fun objectSize(path: String): Int? = (if (path.isBlank()) obj else obj.resolveJsonPathElement(path) as? JsonObject)?.size
}

internal class AttributeTypeCache(
    private val importEngine: ImportEngine,
) {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, AttributeTypeId>()

    suspend fun getOrCreate(name: String): AttributeTypeId =
        mutex.withLock { cache.getOrPut(name) { importEngine.getOrCreateAttributeType(name) } }
}

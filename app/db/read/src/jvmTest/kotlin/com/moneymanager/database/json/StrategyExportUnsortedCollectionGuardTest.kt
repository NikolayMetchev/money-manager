package com.moneymanager.database.json

import com.moneymanager.domain.model.accountmapping.export.AccountMappingsExport
import com.moneymanager.domain.model.apistrategy.ApiRequestSigningConfig
import com.moneymanager.domain.model.apistrategy.SigPart
import com.moneymanager.domain.model.apistrategy.export.ApiStrategyExport
import com.moneymanager.domain.model.csvstrategy.ConversionConfig
import com.moneymanager.domain.model.csvstrategy.RowPreprocessingRule
import com.moneymanager.domain.model.csvstrategy.export.AccountLookupExport
import com.moneymanager.domain.model.csvstrategy.export.CsvStrategyExport
import com.moneymanager.domain.model.csvstrategy.export.DirectColumnExport
import com.moneymanager.domain.model.csvstrategy.export.RegexAccountExport
import com.moneymanager.domain.model.passthrough.export.PassThroughExport
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.jvmErasure
import kotlin.test.Test
import kotlin.test.fail

/**
 * Guardrail against the class of bug fixed under [[strategy-sync-canonical-order]]: a strategy export
 * field that holds an order-insensitive List/Set/Map but serializes in raw insertion order produces a
 * different `canonicalHash` on every device (a chip/row editor's add-order differs per device), so Drive
 * sync reports the same strategy as changed/conflicting forever.
 *
 * Walks every constructor property reachable from each export root (recursing through nested
 * `@Serializable` model types and sealed hierarchies) and requires an explicit decision for every
 * List/Set/Map-typed field: either a `Sorted*Serializer` (`@Serializable(with = ...)`, order-free) or an
 * entry in [orderedByDesign] with a reason (semantic order — reordering would be a real change). A field
 * with neither is almost certainly a bug waiting to reproduce a false-conflict report; a brand new field
 * added without either shows up here instead of silently reintroducing the class of bug.
 */
class StrategyExportUnsortedCollectionGuardTest {
    private val modelPackagePrefix = "com.moneymanager.domain.model"

    /** Collection-typed fields whose order is semantic; see the comment at each field's declaration. */
    private val orderedByDesign: Set<Pair<KClass<*>, String>> =
        setOf(
            // Positional ("ancestor[N]." expressions) / first-match-wins / sequential-mutation semantics.
            ApiStrategyExport::class to "ancestorEndpoints",
            ApiStrategyExport::class to "builtInCounterpartyRules",
            ApiStrategyExport::class to "connectInstructions",
            ApiRequestSigningConfig::class to "message",
            SigPart.Sha256::class to "parts",
            CsvStrategyExport::class to "rowPreprocessingRules",
            RegexAccountExport::class to "rules",
            RegexAccountExport::class to "fallbackColumns",
            AccountLookupExport::class to "fallbackColumns",
            DirectColumnExport::class to "fallbackColumns",
            RowPreprocessingRule::class to "columnSwaps",
            ConversionConfig::class to "conversionAccountRules",
            ConversionConfig::class to "pairingKeyColumns",
            PassThroughExport::class to "rules",
        )

    @Test
    fun `every List Set or Map field reachable from a strategy export root is sorted or explicitly ordered`() {
        val roots =
            listOf(
                CsvStrategyExport::class,
                ApiStrategyExport::class,
                AccountMappingsExport::class,
                PassThroughExport::class,
            )
        val visited = mutableSetOf<KClass<*>>()
        val offenders = mutableListOf<String>()
        roots.forEach { visit(it, visited, offenders) }

        if (offenders.isNotEmpty()) {
            fail(
                "Found order-insensitive List/Set/Map field(s) with no Sorted*Serializer and no " +
                    "entry in orderedByDesign - either annotate with a Sorted*Serializer (see " +
                    "SortedSerializers.kt) or add a documented entry to orderedByDesign if the order " +
                    "genuinely matters:\n" + offenders.joinToString("\n"),
            )
        }
    }

    private fun visit(
        klass: KClass<*>,
        visited: MutableSet<KClass<*>>,
        offenders: MutableList<String>,
    ) {
        if (!visited.add(klass)) return
        if (klass.isSealed) {
            klass.sealedSubclasses.forEach { visit(it, visited, offenders) }
            return
        }
        val ctor = klass.primaryConstructor ?: return
        val propertiesByName = klass.memberProperties.associateBy { it.name }

        for (param in ctor.parameters) {
            val name = param.name ?: continue
            val erasure = param.type.jvmErasure
            val isCollection =
                erasure.isSubclassOf(List::class) || erasure.isSubclassOf(Set::class) || erasure.isSubclassOf(Map::class)

            if (isCollection) {
                val serializableAnnotation = propertiesByName[name]?.annotations?.filterIsInstance<Serializable>()?.firstOrNull()
                val hasSortedSerializer = serializableAnnotation?.with?.simpleName?.startsWith("Sorted") == true
                if (!hasSortedSerializer && (klass to name) !in orderedByDesign) {
                    offenders += "${klass.qualifiedName}.$name : ${param.type}"
                }
            }

            val nestedTypes = if (isCollection) param.type.arguments.mapNotNull { it.type } else listOf(param.type)
            nestedTypes.forEach { type ->
                val nestedErasure = type.jvmErasure
                if (nestedErasure.qualifiedName?.startsWith(modelPackagePrefix) == true) {
                    visit(nestedErasure, visited, offenders)
                }
            }
        }
    }
}

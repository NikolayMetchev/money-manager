package com.moneymanager.domain.model.csvstrategy

import com.moneymanager.domain.model.serialization.SortedListSerializer
import com.moneymanager.domain.model.serialization.SortedStringSetSerializer
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Everything a CSV import strategy configures — the one declaration of its fields, persisted whole as
 * `csv_import_strategy.config_json` and embedded in the portable export. Generic over the
 * field-mapping type [M] because field mappings are the only part that references database entities:
 * a [CsvImportStrategy] holds [FieldMapping]s (by id), its export holds `FieldMappingExport`s (by name).
 *
 * Order-insensitive collections use canonical (sorted) serializers so the same strategy always
 * serializes to identical bytes on every device; a few lists have semantic order (see their own
 * comments) and keep default insertion-order serialization instead.
 *
 * @property identificationColumns Set of column names used to auto-identify this strategy
 *                                 when importing a CSV file. Matching is exact and order-independent.
 * @property fieldMappings Map of TransferField to the mapping defining how each field is populated
 * @property rowPreprocessingRules Rules that may swap column values / flip accounts per row
 *                                 before field mappings run (see [RowPreprocessingRule])
 * @property companionTransactionRules Rules flagging imported transfers that require a manually
 *                                     entered companion transaction (see [CompanionTransactionRule])
 * @property contentMatchRules Rules that auto-detect this strategy from row content when the column
 *                             set is fixed and cannot distinguish formats (see [ContentMatchRule]).
 * @property fileNamePattern Optional regex matched (case-insensitively, anywhere) against the
 *                           imported file's original name. The strongest selection signal for
 *                           sources whose exports share a column set but differ by filename
 *                           (e.g. crypto.com's card_/fiat_/crypto_transactions_record files).
 * @property crossSourceReconcileWindowSeconds When set, a row that fuzzy-matches an existing
 *                                             transfer from a different source (same accounts and
 *                                             amount, timestamps within this window) is imported
 *                                             but tagged excluded and linked as reconciled instead
 *                                             of counting twice. Null disables reconciliation.
 * @property conversionConfig When set, describes how this source expresses asset conversions as
 *                            separate debited/credited rows; the importer routes the legs through a
 *                            shared counterparty account and links each debit to its credit (see
 *                            [ConversionConfig]). Null when the source has no such conversions.
 * @property fundingAttributeMatch When set, resolves each row's hidden funding account by matching a
 *                             CSV column against an account-attribute type (see [AttributeAccountMatch];
 *                             e.g. Curve's "Funding Card Last 4 Digits" column against the `card-last4`
 *                             attribute). A row whose value resolves to a single account is reconciled
 *                             against an unconsumed funding leg into the row's source account (same
 *                             amount+currency within [crossSourceReconcileWindowSeconds]), ignoring the
 *                             merchant. Null disables funding reconciliation.
 * @property tradeGroupConfig When set, describes how this source splits one trade across several rows
 *                            sharing a timestamp; the importer assembles each such group into a single
 *                            `trade` on the owner account (see [TradeGroupConfig]). Null when every
 *                            cross-asset movement already arrives on one row.
 */
@Serializable
data class CsvStrategyConfig<out M>(
    @Serializable(with = SortedStringSetSerializer::class)
    val identificationColumns: Set<String>,
    @Serializable(with = SortedFieldMappingsSerializer::class)
    val fieldMappings: Map<TransferField, M>,
    @Serializable(with = SortedAttributeMappingListSerializer::class)
    val attributeMappings: List<AttributeColumnMapping> = emptyList(),
    // Rules apply sequentially and each can affect what the next rule's conditions see (not
    // first-match) - order is semantic, keeps default insertion-order serialization.
    val rowPreprocessingRules: List<RowPreprocessingRule> = emptyList(),
    @Serializable(with = SortedCompanionTransactionRuleListSerializer::class)
    val companionTransactionRules: List<CompanionTransactionRule> = emptyList(),
    @Serializable(with = SortedContentMatchRuleListSerializer::class)
    val contentMatchRules: List<ContentMatchRule> = emptyList(),
    val fileNamePattern: String? = null,
    val crossSourceReconcileWindowSeconds: Long? = null,
    val conversionConfig: ConversionConfig? = null,
    // Omitted from JSON when null (unlike the fields above, which encode their null under the export
    // codec's encodeDefaults=true) so ADDING this field did not change the canonical hash of every
    // existing strategy — only a strategy that actually sets it (Curve) rehashed. Prevents a spurious
    // "all strategies changed" on catalog/Drive sync. See StrategyArtifactCodec.canonicalHash.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val fundingAttributeMatch: AttributeAccountMatch? = null,
    // Same NEVER-encode rationale: only a strategy that assembles trades from row groups rehashes.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val tradeGroupConfig: TradeGroupConfig? = null,
) {
    /** This config with each field mapping replaced by [transform]'s result, everything else unchanged. */
    fun <N> mapFieldMappings(transform: (M) -> N): CsvStrategyConfig<N> =
        CsvStrategyConfig(
            identificationColumns = identificationColumns,
            fieldMappings = fieldMappings.mapValues { (_, mapping) -> transform(mapping) },
            attributeMappings = attributeMappings,
            rowPreprocessingRules = rowPreprocessingRules,
            companionTransactionRules = companionTransactionRules,
            contentMatchRules = contentMatchRules,
            fileNamePattern = fileNamePattern,
            crossSourceReconcileWindowSeconds = crossSourceReconcileWindowSeconds,
            conversionConfig = conversionConfig,
            fundingAttributeMatch = fundingAttributeMatch,
            tradeGroupConfig = tradeGroupConfig,
        )
}

/** Serializes the field-mappings map with its keys in [TransferField] name order. */
class SortedFieldMappingsSerializer<M>(
    keySerializer: KSerializer<TransferField>,
    valueSerializer: KSerializer<M>,
) : KSerializer<Map<TransferField, M>> {
    private val delegate = MapSerializer(keySerializer, valueSerializer)
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: Map<TransferField, M>,
    ) = delegate.serialize(encoder, sortedByField(value))

    override fun deserialize(decoder: Decoder): Map<TransferField, M> = sortedByField(delegate.deserialize(decoder))

    private fun sortedByField(value: Map<TransferField, M>): Map<TransferField, M> =
        value.entries.sortedBy { it.key.name }.associate { it.key to it.value }
}

/** Serializes attribute-column mappings sorted by [AttributeColumnMapping]'s natural order. */
object SortedAttributeMappingListSerializer : SortedListSerializer<AttributeColumnMapping>(AttributeColumnMapping.serializer())

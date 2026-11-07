package com.moneymanager.domain.model.csvstrategy

import com.moneymanager.domain.model.rules.AssetCodeRules
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.SettlementWindow
import com.moneymanager.domain.model.rules.SortedConditionListSerializer
import com.moneymanager.domain.model.rules.StrategyRule
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
 *                                 when importing a CSV file. A file must have all of them, and no column that
 *                                 isn't also in [optionalColumns]; order doesn't matter.
 * @property optionalColumns Columns a file of this format may or may not have (a later export added them), so
 *                           one strategy covers every version of the export. A rule reads one with
 *                           `Condition(column, EXISTS)`; any other read of an optional column a file lacks
 *                           sees a blank value rather than failing.
 * @property fieldMappings Map of TransferField to the mapping defining how each field is populated
 * @property rules The strategy's if/then rules, run in order over each row before the field mappings
 *                 parse it (see [StrategyRule]): they rewrite cells, name the accounts, set the direction,
 *                 tag attributes, exclude rows, book fees and foreign amounts, route rows through a
 *                 conduit and mark the legs of [legGroups].
 * @property contentMatchRules Conditions that auto-detect this strategy from row content when the column
 *                             set is fixed and cannot distinguish formats (e.g. QIF, where every file has
 *                             the same columns): a sampled row content-matches when ANY of them holds. A
 *                             strategy with none never positively content-matches and so acts as the
 *                             fallback.
 * @property fileNamePattern Optional regex matched (case-insensitively, anywhere) against the
 *                           imported file's original name. The strongest selection signal for
 *                           sources whose exports share a column set but differ by filename
 *                           (e.g. crypto.com's card_/fiat_/crypto_transactions_record files).
 * @property crossSourceReconcileWindow When set, a row that fuzzy-matches an existing transfer from a
 *                                      different source (same accounts and amount, within this window)
 *                                      is imported but tagged excluded and linked as reconciled instead
 *                                      of counting twice. Null disables reconciliation.
 * @property duplicateDateTolerance How far apart in time a row and an existing transfer of the same amount, a shared
 *                                  account and a similar description may be and still be skipped as a duplicate
 *                                  (a bank re-export drifts by a day or two). Null: three days. A source that
 *                                  repeats identical rows day after day (a daily interest payout) sets it to zero, so
 *                                  only the same instant is a duplicate. Applies to strategies without unique-id columns.
 * @property placeholderReconcileWindow How far apart a placeholder leg (a counterparty the row can only
 *                                      describe, such as a card statement's "PAYMENT RECEIVED") and the
 *                                      record naming the far end may be. Null: three days, as clock time.
 *                                      Business days suit a statement that books a payment after a bank
 *                                      holiday weekend.
 * @property legGroups How this source splits single movements — trades, conversions — across several
 *                  rows, and how those rows are reassembled (see [LegGroupRule]). Empty when every
 *                  movement arrives on one row.
 * @property fundingAttributeMatch When set, resolves each row's hidden funding account by matching a
 *                             CSV column against an account-attribute type (see [AttributeAccountMatch];
 *                             e.g. Curve's "Funding Card Last 4 Digits" column against the `card-last4`
 *                             attribute). A row whose value resolves to a single account is reconciled
 *                             against an unconsumed funding leg into the row's source account (same
 *                             amount+currency within [crossSourceReconcileWindow]), ignoring the
 *                             merchant. Null disables funding reconciliation.
 * @property assetCodes How the source's asset codes are normalised, applied to every currency column before lookup.
 * @property reconciliation When set, this strategy imports a reconciliation source into shadow
 *                          accounts only (see [ReconciliationConfig]). Null for ordinary strategies.
 * @property pdfLines When set, this strategy reads PDFs: these rules assemble a document's lines into the
 *                    rows everything else here then reads (see [PdfLineConfig]). Null for CSV, QIF and Excel.
 */
@Serializable
data class CsvStrategyConfig<out M>(
    @Serializable(with = SortedStringSetSerializer::class)
    val identificationColumns: Set<String>,
    @Serializable(with = SortedFieldMappingsSerializer::class)
    val fieldMappings: Map<TransferField, M>,
    // Rules apply in order, a rewrite is seen by the rules after it and single-valued outcomes keep the
    // first claim - order is semantic, keeps insertion-order serialization. NEVER-encoded when empty.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val rules: List<StrategyRule> = emptyList(),
    @Serializable(with = SortedConditionListSerializer::class)
    val contentMatchRules: List<Condition> = emptyList(),
    val fileNamePattern: String? = null,
    val crossSourceReconcileWindow: SettlementWindow? = null,
    // NEVER-encoded when null, so only a strategy that sets it rehashes.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val placeholderReconcileWindow: SettlementWindow? = null,
    // NEVER-encoded when null, for the same reason: only a strategy that sets it rehashes.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val duplicateDateTolerance: SettlementWindow.Elapsed? = null,
    // Omitted from JSON when null (unlike the fields above, which encode their null under the export
    // codec's encodeDefaults=true) so ADDING this field did not change the canonical hash of every
    // existing strategy — only a strategy that actually sets it (Curve) rehashed. Prevents a spurious
    // "all strategies changed" on catalog/Drive sync. See StrategyArtifactCodec.canonicalHash.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val fundingAttributeMatch: AttributeAccountMatch? = null,
    // Groups are looked up by name; kept in insertion order like the rules that name them. NEVER-encoded
    // when empty.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val legGroups: List<LegGroupRule> = emptyList(),
    // Same NEVER-encode rationale: only reconciliation-source strategies rehash.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val reconciliation: ReconciliationConfig? = null,
    // NEVER-encoded when empty, same rationale.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val assetCodes: AssetCodeRules = AssetCodeRules(),
    // NEVER-encoded when null, same rationale.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val pdfLines: PdfLineConfig? = null,
    // NEVER-encoded when empty, same rationale.
    @Serializable(with = SortedStringSetSerializer::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val optionalColumns: Set<String> = emptySet(),
) {
    /**
     * The columns the rules set (`RuleAction.SetValue`), in rule order. A file that lacks one still has it once the
     * rules have run, so a mapping can read a value that sits under a different column in each export.
     */
    fun ruleColumns(): List<String> =
        rules
            .flatMap { rule -> rule.actions.filterIsInstance<RuleAction.SetValue>().map { it.path } }
            .distinct()

    /** This config with each field mapping replaced by [transform]'s result, everything else unchanged. */
    fun <N> mapFieldMappings(transform: (M) -> N): CsvStrategyConfig<N> =
        CsvStrategyConfig(
            identificationColumns = identificationColumns,
            optionalColumns = optionalColumns,
            fieldMappings = fieldMappings.mapValues { (_, mapping) -> transform(mapping) },
            rules = rules,
            contentMatchRules = contentMatchRules,
            fileNamePattern = fileNamePattern,
            crossSourceReconcileWindow = crossSourceReconcileWindow,
            placeholderReconcileWindow = placeholderReconcileWindow,
            duplicateDateTolerance = duplicateDateTolerance,
            fundingAttributeMatch = fundingAttributeMatch,
            legGroups = legGroups,
            reconciliation = reconciliation,
            assetCodes = assetCodes,
            pdfLines = pdfLines,
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

package com.moneymanager.importdirectory

import com.moneymanager.csvimporter.AttributeAccountMatcher
import com.moneymanager.csvimporter.BulkApplyResult
import com.moneymanager.csvimporter.DirectoryRedownloadResult
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.ImportDirectoryId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.passthrough.PassThroughAccount

/** Why something can't be finished without a person deciding something. */
enum class NeedsAttentionReason(
    val description: String,
) {
    NO_STRATEGY("no strategy matches it"),
    NEEDS_SOURCE_ACCOUNT("its strategy needs a source account and no import folder gives one"),

    /** A re-download whose new copy lost rows that were already imported: unimport it to replace it. */
    REDOWNLOAD_BLOCKED("its new copy no longer has rows that were imported, so it was left as it was"),

    /** A re-download changed the file's columns, so its imported rows may now map differently. */
    REIMPORT_SUGGESTED("its columns changed, so re-importing it may change its transactions"),

    /** A card or funding reference in an imported file that no account owns (the Card Last-4 tab). */
    UNASSIGNED_CARD("no account owns this card reference"),
}

/** A file (or, for [NeedsAttentionReason.UNASSIGNED_CARD], a card reference) an unattended run leaves alone, and why. */
data class NeedsAttention(
    val subject: String,
    val reason: NeedsAttentionReason,
)

/**
 * What a CSV "import all" over [files] will do, worked out before anything is written: each file's
 * matched strategy ([matches], null = no match), the account each file's import directory gives it, the
 * folders whose files need an account none is set for ([folders]: applying names one after the folder),
 * and the files a person must resolve ([needsAttention]). The rest is what the apply step needs.
 */
data class CsvImportAllPlan(
    val files: List<CsvImport>,
    val matches: Map<CsvImportId, CsvImportStrategy?>,
    val directoryAccounts: Map<CsvImportId, AccountId>,
    val folders: Map<CsvImportId, ImportDirectoryId>,
    val needsAttention: List<NeedsAttention>,
    val strategies: List<CsvImportStrategy>,
    val currencies: List<Currency>,
    val passThroughAccounts: List<PassThroughAccount>,
    val attributeAccountMatchers: Map<String, AttributeAccountMatcher>,
    private val needsSourceAccountIds: Set<CsvImportId>,
) {
    val skippedNoStrategy: Int get() = needsAttention.count { it.reason == NeedsAttentionReason.NO_STRATEGY }

    /** Some file's strategy needs a source account its folder doesn't give, so a shared pick is required. */
    val needsSourceAccount: Boolean get() = needsSourceAccountIds.isNotEmpty()

    /** The plan without the files that need a person, for a run nobody is watching. */
    fun unattended(): CsvImportAllPlan {
        val blocked = needsSourceAccountIds + matches.filterValues { it == null }.keys
        return copy(files = files.filter { it.id !in blocked }, needsAttention = emptyList(), needsSourceAccountIds = emptySet())
    }
}

/**
 * What an unattended [ImportDirectoryService.importAll] did: an optional re-download, the download, then
 * the apply (empty when the run only downloaded), plus what it left for a person.
 */
data class ImportAllResult(
    val download: DownloadAllResult,
    val csv: BulkApplyResult,
    val needsAttention: List<NeedsAttention>,
    val redownload: DirectoryRedownloadResult? = null,
) {
    fun summary(): String =
        buildString {
            redownload?.let { append(it.summary()).append(' ') }
            append(download.summary())
            append(" Files: ${csv.toSummary()}.")
            if (needsAttention.isNotEmpty()) append(" ${needsAttention.size} item(s) need attention.")
        }
}

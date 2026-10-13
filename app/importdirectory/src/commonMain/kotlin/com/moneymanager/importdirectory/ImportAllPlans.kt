package com.moneymanager.importdirectory

import com.moneymanager.csvimporter.AttributeAccountMatcher
import com.moneymanager.csvimporter.BulkImportResult
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.QifImportId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.model.qif.QifImport

/** Why a file can't be imported without a person deciding something. */
enum class NeedsAttentionReason(
    val description: String,
) {
    NO_STRATEGY("no strategy matches it"),
    NEEDS_SOURCE_ACCOUNT("its strategy needs a source account and its folder has none"),
}

/** A file an unattended "import all" leaves alone, and why. */
data class NeedsAttention(
    val fileName: String,
    val reason: NeedsAttentionReason,
)

/**
 * What a CSV "import all" over [files] will do, worked out before anything is written: each file's
 * matched strategy ([matches], null = no match), the account each file's import directory gives it, and
 * the files a person must resolve ([needsAttention]). The rest is what the apply step needs.
 */
data class CsvImportAllPlan(
    val files: List<CsvImport>,
    val matches: Map<CsvImportId, CsvImportStrategy?>,
    val directoryAccounts: Map<CsvImportId, AccountId>,
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
 * What a QIF "import all" over [files] will do. QIF always matches *some* strategy when one exists, so
 * the question is mostly the source account: [directoryAccounts] covers files from a folder with one,
 * and [defaultSourceAccountId] (the last account a QIF import used) preselects the shared pick.
 */
data class QifImportAllPlan(
    val files: List<QifImport>,
    val directoryAccounts: Map<QifImportId, AccountId>,
    val defaultSourceAccountId: AccountId?,
    val needsAttention: List<NeedsAttention>,
    val strategies: List<CsvImportStrategy>,
    val currencies: List<Currency>,
    private val blockedIds: Set<QifImportId>,
) {
    /** Some file has no folder account, so the dialog offers a shared one. */
    val needsSourceAccount: Boolean get() = files.any { it.id !in directoryAccounts }

    fun unattended(): QifImportAllPlan =
        copy(
            files =
                files.filter {
                    it.id !in blockedIds
                },
            needsAttention = emptyList(),
            blockedIds = emptySet(),
        )
}

/** What a CSV "re-import all" over [files] will do: each file's strategy (last applied, else matched). */
data class CsvReimportAllPlan(
    val files: List<CsvImport>,
    val matches: Map<CsvImportId, CsvImportStrategy?>,
    val strategies: List<CsvImportStrategy>,
    val currencies: List<Currency>,
    val passThroughAccounts: List<PassThroughAccount>,
    val attributeAccountMatchers: Map<String, AttributeAccountMatcher>,
) {
    val skippedNoStrategy: Int get() = matches.count { it.value == null }
}

/** What a QIF "re-import all" over [files] will do, with the dialog's preselected account and currency. */
data class QifReimportAllPlan(
    val files: List<QifImport>,
    val defaultSourceAccountId: AccountId?,
    val defaultCurrencyId: CurrencyId?,
    val strategies: List<CsvImportStrategy>,
    val currencies: List<Currency>,
)

/** What an unattended [ImportDirectoryService.importAll] did: download, then apply, plus what it left for a person. */
data class ImportAllResult(
    val download: DownloadAllResult,
    val csv: BulkImportResult,
    val qif: BulkImportResult,
    val needsAttention: List<NeedsAttention>,
) {
    fun summary(): String =
        buildString {
            append(download.summary())
            append(" CSV: ${csv.toSummary()}.")
            append(" QIF: ${qif.toSummary()}.")
            if (needsAttention.isNotEmpty()) append(" ${needsAttention.size} file(s) need attention.")
        }
}

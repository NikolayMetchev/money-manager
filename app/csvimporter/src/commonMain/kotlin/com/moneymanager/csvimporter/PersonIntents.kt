package com.moneymanager.csvimporter

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Source
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.ImportOwnershipIntent
import com.moneymanager.importengineapi.ImportPersonIntent
import com.moneymanager.importengineapi.LocalPersonKey
import com.moneymanager.importengineapi.PersonMatchKey
import com.moneymanager.importengineapi.normalizeNameKey

internal class PersonIntents(
    val people: List<ImportPersonIntent>,
    val ownerships: List<ImportOwnershipIntent>,
)

/**
 * Personal counterparties (resolved via person-flagged account namings in the strategy's
 * rules) become People with an ownership link to their counterparty account, in
 * addition to the account itself. The engine dedups people by name key and ownerships by
 * (person, account), so repeats across rows/files and re-imports collapse. Mirrors the QIF importer,
 * which reuses this same CsvTransferMapper-computed signal.
 *
 * The counterparty account id is read from the resolved transfer (the side that isn't the source
 * account) rather than by name lookup, so a counterparty that resolved to an existing account with
 * different casing/name still gets its Person + ownership. The value is paired with the first CSV
 * row that referenced the person, so the person's audit source links back to that exact row.
 */
internal fun personIntentsFor(
    validTransfers: List<CsvTransferWithAttributes>,
    selectedSourceAccountId: AccountId?,
    accountsByName: Map<String, Account>,
    csvImportId: CsvImportId,
): PersonIntents {
    val personLinks = LinkedHashMap<String, Pair<AccountId, Long>>()
    validTransfers.forEach { row ->
        val name = row.personalCounterpartyName?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
        val counterpartyAccountId =
            when (selectedSourceAccountId) {
                row.transfer.sourceAccountId -> row.transfer.targetAccountId
                row.transfer.targetAccountId -> row.transfer.sourceAccountId
                else -> accountsByName[name]?.id
            } ?: return@forEach
        val previous = personLinks[name]
        personLinks[name] =
            (previous?.first ?: counterpartyAccountId) to minOf(previous?.second ?: row.rowIndex, row.rowIndex)
    }
    val peopleToCreate =
        personLinks.map { (name, link) ->
            val parts = name.split(Regex("\\s+"))
            ImportPersonIntent(
                key = LocalPersonKey(name),
                match = PersonMatchKey.ByNameKey(normalizeNameKey(name)),
                firstName = parts.first(),
                lastName = parts.drop(1).joinToString(" ").ifBlank { null },
                source = Source.Csv(csvImportId, link.second),
            )
        }
    val personOwnerships =
        personLinks.map { (name, link) ->
            ImportOwnershipIntent(
                personKey = LocalPersonKey(name),
                account = AccountRef.Existing(link.first),
                source = Source.Csv(csvImportId, link.second),
            )
        }
    return PersonIntents(peopleToCreate, personOwnerships)
}

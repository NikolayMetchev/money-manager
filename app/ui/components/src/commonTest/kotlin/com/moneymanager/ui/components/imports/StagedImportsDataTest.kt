package com.moneymanager.ui.components.imports

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.ImportDirectoryId
import com.moneymanager.domain.model.QifImportId
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.importdirectory.ImportDirectoryProvider
import com.moneymanager.domain.model.qif.QifImport
import com.moneymanager.domain.model.rules.ValueExpr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

class StagedImportsDataTest {
    private val epoch = Instant.fromEpochMilliseconds(0)
    private val file =
        QifImport(
            id = QifImportId(Uuid.random()),
            originalFileName = "amex.qif",
            importTimestamp = epoch,
            recordCount = 185,
            unsupportedCount = 2,
            accountType = "CCARD",
            deviceInfo = DeviceInfo.Jvm("os", "machine"),
            fileChecksum = "amex.qif",
            fileLastModified = epoch,
        )
    private val amexFolder =
        ImportDirectory(
            id = ImportDirectoryId(Uuid.random()),
            name = "Shared with me / Banking / Statements / American Express",
            provider = ImportDirectoryProvider.GDRIVE,
            folderRef = "amex",
            topLevel = false,
            parentId = ImportDirectoryId(Uuid.random()),
            createdAt = epoch,
            updatedAt = epoch,
        )
    private val conduit = Account(id = AccountId(7), name = "Conduit", openingDate = epoch)
    private val amex = Account(id = AccountId(8), name = "American Express", openingDate = epoch)

    @Test
    fun `a file from a folder with no account names the folder account its import creates`() {
        assertEquals("American Express (created at import)", data().sourceAccountName(file))
    }

    @Test
    fun `a file from a folder with no account names an existing account named after the folder`() {
        assertEquals("American Express", data(accounts = listOf(amex)).sourceAccountName(file))
    }

    @Test
    fun `an account set on the folder wins over its name`() {
        assertEquals("Conduit", data(directoryAccounts = mapOf(file.id to conduit.id)).sourceAccountName(file))
    }

    @Test
    fun `a hard-coded account on the matched strategy wins over the folder`() {
        val mapping = HardCodedAccountMapping(TransferField.SOURCE_ACCOUNT, conduit.id)
        assertEquals("Conduit", data(strategy = strategy(mapping)).sourceAccountName(file))
    }

    @Test
    fun `a strategy reading the account from the row ignores the folder`() {
        val mapping = DirectColumnMapping(TransferField.SOURCE_ACCOUNT, ValueExpr(listOf("Account")))
        assertEquals(FROM_EACH_ROW, data(strategy = strategy(mapping)).sourceAccountName(file))
    }

    @Test
    fun `a manually added file has no source account until import`() {
        assertEquals(null, data(importDirectories = emptyMap()).sourceAccountName(file))
    }

    private fun strategy(sourceMapping: FieldMapping) =
        CsvImportStrategy(
            id = CsvImportStrategyId(Uuid.random()),
            name = "QIF",
            config =
                CsvStrategyConfig(
                    identificationColumns = emptySet(),
                    fieldMappings = mapOf(TransferField.SOURCE_ACCOUNT to sourceMapping),
                ),
            createdAt = epoch,
            updatedAt = epoch,
        )

    private fun data(
        strategy: CsvImportStrategy? = null,
        accounts: List<Account> = listOf(conduit),
        directoryAccounts: Map<QifImportId, AccountId> = emptyMap(),
        importDirectories: Map<QifImportId, List<ImportDirectory>> = mapOf(file.id to listOf(amexFolder)),
    ) = StagedImportsData(
        directories = listOf(amexFolder),
        importDirectories = importDirectories,
        matchedStrategies = mapOf(file.id to strategy),
        strategies = listOfNotNull(strategy),
        accounts = accounts,
        directoryAccounts = directoryAccounts,
    )
}

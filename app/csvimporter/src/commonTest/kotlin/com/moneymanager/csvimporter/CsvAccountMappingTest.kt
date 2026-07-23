package com.moneymanager.csvimporter

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.accountmapping.AccountMapping
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csvstrategy.AccountLookupMapping
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMappingId
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.RegexAccountMapping
import com.moneymanager.domain.model.csvstrategy.RegexRule
import com.moneymanager.domain.model.csvstrategy.TransferField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Tests for CsvAccountMapping integration with CsvTransferMapper.
 *
 * These tests verify that persisted account mappings are applied BEFORE
 * the standard name-based account lookup, enabling:
 * 1. Renamed accounts to be matched by their original CSV values
 * 2. Multiple CSV values to be consolidated to a single account
 */
class CsvAccountMappingTest {
    private val testCurrencyId = CurrencyId(1L)
    private val testCurrency =
        Currency(
            id = testCurrencyId,
            code = "GBP",
            name = "British Pound",
        )

    private val testSourceAccountId = AccountId(1)
    private val strategyId = CsvImportStrategyId(Uuid.random())

    private val columns =
        listOf(
            CsvColumn(CsvColumnId(Uuid.random()), 0, "Date"),
            CsvColumn(CsvColumnId(Uuid.random()), 1, "Description"),
            CsvColumn(CsvColumnId(Uuid.random()), 2, "Amount"),
            CsvColumn(CsvColumnId(Uuid.random()), 3, "Payee"),
        )

    private fun createStrategy(): CsvImportStrategy {
        val now = Clock.System.now()
        return CsvImportStrategy(
            id = strategyId,
            name = "Test Strategy",
            identificationColumns = setOf("Date", "Description", "Amount"),
            fieldMappings =
                mapOf(
                    TransferField.SOURCE_ACCOUNT to
                        HardCodedAccountMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.SOURCE_ACCOUNT,
                            accountId = testSourceAccountId,
                        ),
                    TransferField.TARGET_ACCOUNT to
                        AccountLookupMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.TARGET_ACCOUNT,
                            columnName = "Payee",
                        ),
                    TransferField.TIMESTAMP to
                        DateTimeParsingMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.TIMESTAMP,
                            dateColumnName = "Date",
                            dateFormat = "dd/MM/yyyy",
                        ),
                    TransferField.DESCRIPTION to
                        DirectColumnMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.DESCRIPTION,
                            columnName = "Description",
                        ),
                    TransferField.AMOUNT to
                        AmountParsingMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.AMOUNT,
                            mode = AmountMode.SINGLE_COLUMN,
                            amountColumnName = "Amount",
                        ),
                    TransferField.CURRENCY to
                        HardCodedCurrencyMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.CURRENCY,
                            currencyId = testCurrencyId,
                        ),
                ),
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun createAccountMapping(
        id: Long,
        pattern: String,
        accountId: AccountId,
        strategyId: CsvImportStrategyId? = null,
    ): AccountMapping {
        val now = Clock.System.now()
        return AccountMapping(
            id = id,
            strategyId = strategyId,
            valuePattern = Regex(pattern, RegexOption.IGNORE_CASE),
            accountId = accountId,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun mapperWith(
        accountMappings: List<AccountMapping>,
        existingAccounts: Map<String, Account> = emptyMap(),
    ): CsvTransferMapper =
        CsvTransferMapper(
            strategy = createStrategy(),
            columns = columns,
            existingAccounts = existingAccounts,
            existingCurrencies = mapOf(testCurrencyId to testCurrency),
            existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
            accountMappings = accountMappings,
        )

    private fun payeeRow() = CsvRow(rowIndex = 1, values = listOf("15/12/2024", "Transfer", "-50.00", "Foo"))

    @Test
    fun `global mapping applies under any strategy`() {
        val target = AccountId(30)
        val mapper = mapperWith(listOf(createAccountMapping(1, "^Foo$", target)))
        val result = mapper.mapRow(payeeRow())
        assertIs<MappingResult.Success>(result)
        assertEquals(target, result.transfer.targetAccountId)
    }

    @Test
    fun `mapping scoped to a different strategy is ignored`() {
        val target = AccountId(30)
        val otherStrategy = CsvImportStrategyId(Uuid.random())
        val mapper = mapperWith(listOf(createAccountMapping(1, "^Foo$", target, strategyId = otherStrategy)))
        val result = mapper.mapRow(payeeRow())
        assertIs<MappingResult.Success>(result)
        // Not matched: "Foo" is discovered as a new account instead of routing to the scoped target.
        assertEquals("Foo", result.newAccountName)
    }

    @Test
    fun `strategy-specific mapping wins over a global one for the same value`() {
        val globalTarget = AccountId(30)
        val strategyTarget = AccountId(31)
        val mapper =
            mapperWith(
                listOf(
                    createAccountMapping(1, "^Foo$", globalTarget),
                    createAccountMapping(2, "^Foo$", strategyTarget, strategyId = strategyId),
                ),
            )
        val result = mapper.mapRow(payeeRow())
        assertIs<MappingResult.Success>(result)
        assertEquals(strategyTarget, result.transfer.targetAccountId)
    }

    // ============= Scenario 1: Renamed Account =============

    @Test
    fun `persisted mapping resolves renamed account correctly`() {
        // Account was created as "Nikolay Metchev & Olga Zakharenko" during import
        // User renamed it to "Monzo Joint Account"
        // Mapping: pattern="^Nikolay Metchev & Olga Zakharenko$" -> accountId=1
        val renamedAccountId = AccountId(10)
        // The renamed name
        val renamedAccount =
            Account(
                id = renamedAccountId,
                name = "Monzo Joint Account",
                openingDate = Clock.System.now(),
            )

        // Create exact-match mapping for the original CSV value
        val mapping =
            createAccountMapping(
                id = 1,
                pattern = "^Nikolay Metchev & Olga Zakharenko$",
                accountId = renamedAccountId,
            )

        val mapper =
            CsvTransferMapper(
                strategy = createStrategy(),
                columns = columns,
                existingAccounts = mapOf("Monzo Joint Account" to renamedAccount),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
                accountMappings = listOf(mapping),
            )

        // CSV still has the original name
        val row =
            CsvRow(
                rowIndex = 1,
                values = listOf("15/12/2024", "Transfer", "-50.00", "Nikolay Metchev & Olga Zakharenko"),
            )
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        // Should use the renamed account, not create a new one
        assertEquals(renamedAccountId, result.transfer.targetAccountId)
        // No new account should be created
        assertNull(result.newAccountName)
        // No discovered mapping since we used a persisted one
        assertNull(result.discoveredMapping)
    }

    @Test
    fun `historical name resolves renamed account without a persisted mapping`() {
        // Account created as "Nikolay Metchev & Olga Zakharenko", later renamed to "Monzo Joint Account".
        // No exact mapping is stored (redundant); the old name lives in audit history instead.
        val renamedAccountId = AccountId(10)
        val renamedAccount =
            Account(
                id = renamedAccountId,
                name = "Monzo Joint Account",
                openingDate = Clock.System.now(),
            )

        val mapper =
            CsvTransferMapper(
                strategy = createStrategy(),
                columns = columns,
                existingAccounts = mapOf("Monzo Joint Account" to renamedAccount),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
                accountMappings = emptyList(),
                // getPreviousAccountNames() lowercases keys.
                historicalAccountNames = mapOf("nikolay metchev & olga zakharenko" to renamedAccountId),
            )

        val row =
            CsvRow(
                rowIndex = 1,
                values = listOf("15/12/2024", "Transfer", "-50.00", "Nikolay Metchev & Olga Zakharenko"),
            )
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        // Resolves to the renamed account via history, not a new account.
        assertEquals(renamedAccountId, result.transfer.targetAccountId)
        assertNull(result.newAccountName)
        assertNull(result.discoveredMapping)
    }

    // ============= Scenario 2: Consolidating Accounts =============

    @Test
    fun `persisted mapping routes to existing account with regex pattern`() {
        // Account "Paxos" exists (ID=21)
        // Mapping: pattern="(?i).*paxos.*" -> accountId=21
        // CSV value: "Paxos Technology LTD"
        val paxosAccountId = AccountId(21)
        val paxosAccount =
            Account(
                id = paxosAccountId,
                name = "Paxos",
                openingDate = Clock.System.now(),
            )

        val mapping =
            createAccountMapping(
                id = 1,
                pattern = ".*paxos.*",
                accountId = paxosAccountId,
            )

        val mapper =
            CsvTransferMapper(
                strategy = createStrategy(),
                columns = columns,
                existingAccounts = mapOf("Paxos" to paxosAccount),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
                accountMappings = listOf(mapping),
            )

        // CSV has a different paxos variation
        val row =
            CsvRow(
                rowIndex = 1,
                values = listOf("15/12/2024", "Salary", "1500.00", "Paxos Technology LTD"),
            )
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals(paxosAccountId, result.transfer.targetAccountId)
        assertNull(result.newAccountName)
    }

    @Test
    fun `persisted mapping is case insensitive`() {
        val paxosAccountId = AccountId(21)
        val paxosAccount =
            Account(
                id = paxosAccountId,
                name = "Paxos",
                openingDate = Clock.System.now(),
            )

        val mapping =
            createAccountMapping(
                id = 1,
                pattern = ".*paxos.*",
                accountId = paxosAccountId,
            )

        val mapper =
            CsvTransferMapper(
                strategy = createStrategy(),
                columns = columns,
                existingAccounts = mapOf("Paxos" to paxosAccount),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
                accountMappings = listOf(mapping),
            )

        // CSV has uppercase PAXOS
        val row =
            CsvRow(
                rowIndex = 1,
                values = listOf("15/12/2024", "Salary", "1500.00", "PAXOS HOLDINGS"),
            )
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals(paxosAccountId, result.transfer.targetAccountId)
    }

    // ============= First Matching Mapping Wins =============

    @Test
    fun `first matching mapping wins when multiple patterns match`() {
        val account1Id = AccountId(10)
        val account2Id = AccountId(20)

        val account1 =
            Account(
                id = account1Id,
                name = "Account 1",
                openingDate = Clock.System.now(),
            )
        val account2 =
            Account(
                id = account2Id,
                name = "Account 2",
                openingDate = Clock.System.now(),
            )

        // Both patterns match "Test Corp" but mapping with lower id should win
        val mapping1 =
            createAccountMapping(
                id = 1,
                pattern = ".*test.*",
                accountId = account1Id,
            )
        val mapping2 =
            createAccountMapping(
                id = 2,
                pattern = ".*corp.*",
                accountId = account2Id,
            )

        val mapper =
            CsvTransferMapper(
                strategy = createStrategy(),
                columns = columns,
                existingAccounts =
                    mapOf(
                        "Account 1" to account1,
                        "Account 2" to account2,
                    ),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
                accountMappings = listOf(mapping1, mapping2),
            )

        val row =
            CsvRow(
                rowIndex = 1,
                values = listOf("15/12/2024", "Payment", "-100.00", "Test Corp Ltd"),
            )
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        // First mapping (id=1) should win
        assertEquals(account1Id, result.transfer.targetAccountId)
    }

    // ============= Fallback to Name Lookup =============

    @Test
    fun `falls back to name lookup when no persisted mapping matches`() {
        val existingAccountId = AccountId(5)
        val existingAccount =
            Account(
                id = existingAccountId,
                name = "Amazon UK",
                openingDate = Clock.System.now(),
            )

        // Mapping for a different pattern that won't match
        val mapping =
            createAccountMapping(
                id = 1,
                pattern = ".*paxos.*",
                accountId = AccountId(99),
            )

        val mapper =
            CsvTransferMapper(
                strategy = createStrategy(),
                columns = columns,
                existingAccounts = mapOf("Amazon UK" to existingAccount),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
                accountMappings = listOf(mapping),
            )

        val row =
            CsvRow(
                rowIndex = 1,
                values = listOf("15/12/2024", "Purchase", "-50.00", "Amazon UK"),
            )
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        // Should use name lookup since no mapping matched
        assertEquals(existingAccountId, result.transfer.targetAccountId)
    }

    @Test
    fun `identifies new account when no persisted mapping and no existing account`() {
        // No mappings, no existing account with this name
        val mapper =
            CsvTransferMapper(
                strategy = createStrategy(),
                columns = columns,
                existingAccounts = emptyMap(),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
                accountMappings = emptyList(),
            )

        val row =
            CsvRow(
                rowIndex = 1,
                values = listOf("15/12/2024", "Payment", "-100.00", "New Vendor Inc"),
            )
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals("New Vendor Inc", result.newAccountName)
        // Discovered mapping should be captured for auto-capture
        assertEquals("New Vendor Inc", result.discoveredMapping?.csvValue)
    }

    // ============= No New Account When Persisted Mapping Matches =============

    @Test
    fun `does not suggest new account when persisted mapping matches`() {
        // Even if the target account name doesn't match the CSV value,
        // we should not create a new account when a mapping matches
        val targetAccountId = AccountId(30)
        // Different from CSV value
        val targetAccount =
            Account(
                id = targetAccountId,
                name = "Consolidated Payments",
                openingDate = Clock.System.now(),
            )

        val mapping =
            createAccountMapping(
                id = 1,
                pattern = "^Vendor XYZ$",
                accountId = targetAccountId,
            )

        val mapper =
            CsvTransferMapper(
                strategy = createStrategy(),
                columns = columns,
                existingAccounts = mapOf("Consolidated Payments" to targetAccount),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
                accountMappings = listOf(mapping),
            )

        val row =
            CsvRow(
                rowIndex = 1,
                values = listOf("15/12/2024", "Payment", "-100.00", "Vendor XYZ"),
            )
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals(targetAccountId, result.transfer.targetAccountId)
        // No new account should be suggested
        assertNull(result.newAccountName)
        assertNull(result.discoveredMapping)
    }

    // ============= prepareImport with Account Mappings =============

    @Test
    fun `prepareImport uses persisted mappings for multiple rows`() {
        val monzoAccountId = AccountId(10)
        val monzoAccount =
            Account(
                id = monzoAccountId,
                name = "Monzo Joint Account",
                openingDate = Clock.System.now(),
            )

        val paxosAccountId = AccountId(21)
        val paxosAccount =
            Account(
                id = paxosAccountId,
                name = "Paxos",
                openingDate = Clock.System.now(),
            )

        val mappings =
            listOf(
                createAccountMapping(
                    id = 1,
                    pattern = "^Nikolay Metchev & Olga Zakharenko$",
                    accountId = monzoAccountId,
                ),
                createAccountMapping(
                    id = 2,
                    pattern = ".*paxos.*",
                    accountId = paxosAccountId,
                ),
            )

        val mapper =
            CsvTransferMapper(
                strategy = createStrategy(),
                columns = columns,
                existingAccounts =
                    mapOf(
                        "Monzo Joint Account" to monzoAccount,
                        "Paxos" to paxosAccount,
                    ),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
                accountMappings = mappings,
            )

        val rows =
            listOf(
                CsvRow(1, listOf("15/12/2024", "Transfer 1", "-50.00", "Nikolay Metchev & Olga Zakharenko")),
                CsvRow(2, listOf("16/12/2024", "Salary", "1500.00", "Paxos Technology LTD")),
                CsvRow(3, listOf("17/12/2024", "Transfer 2", "-25.00", "Nikolay Metchev & Olga Zakharenko")),
            )

        val preparation = mapper.prepareImport(rows)

        assertEquals(3, preparation.validTransfers.size)
        assertEquals(0, preparation.errorRows.size)
        // No new accounts needed - all mapped to existing
        assertEquals(0, preparation.newAccounts.size)

        // Verify all transfers use correct mapped accounts
        assertEquals(monzoAccountId, preparation.validTransfers[0].transfer.targetAccountId)
        assertEquals(paxosAccountId, preparation.validTransfers[1].transfer.targetAccountId)
        assertEquals(monzoAccountId, preparation.validTransfers[2].transfer.targetAccountId)
    }

    // ============= RegexAccountMapping with Account Mappings =============

    private val columnsWithType =
        listOf(
            CsvColumn(CsvColumnId(Uuid.random()), 0, "Date"),
            CsvColumn(CsvColumnId(Uuid.random()), 1, "Description"),
            CsvColumn(CsvColumnId(Uuid.random()), 2, "Amount"),
            CsvColumn(CsvColumnId(Uuid.random()), 3, "Name"),
            CsvColumn(CsvColumnId(Uuid.random()), 4, "Type"),
        )

    private fun createStrategyWithRegex(): CsvImportStrategy {
        val now = Clock.System.now()
        return CsvImportStrategy(
            id = strategyId,
            name = "Strategy With Regex",
            identificationColumns = setOf("Date", "Description", "Amount", "Name", "Type"),
            fieldMappings =
                mapOf(
                    TransferField.SOURCE_ACCOUNT to
                        HardCodedAccountMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.SOURCE_ACCOUNT,
                            accountId = testSourceAccountId,
                        ),
                    TransferField.TARGET_ACCOUNT to
                        RegexAccountMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.TARGET_ACCOUNT,
                            columnName = "Name",
                            rules =
                                listOf(
                                    RegexRule(pattern = ".*generic.*", accountName = "Generic"),
                                ),
                            fallbackColumns = listOf("Type"),
                        ),
                    TransferField.TIMESTAMP to
                        DateTimeParsingMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.TIMESTAMP,
                            dateColumnName = "Date",
                            dateFormat = "dd/MM/yyyy",
                        ),
                    TransferField.DESCRIPTION to
                        DirectColumnMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.DESCRIPTION,
                            columnName = "Description",
                        ),
                    TransferField.AMOUNT to
                        AmountParsingMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.AMOUNT,
                            mode = AmountMode.SINGLE_COLUMN,
                            amountColumnName = "Amount",
                        ),
                    TransferField.CURRENCY to
                        HardCodedCurrencyMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.CURRENCY,
                            currencyId = testCurrencyId,
                        ),
                ),
            createdAt = now,
            updatedAt = now,
        )
    }

    @Test
    fun `persisted mapping takes precedence over RegexAccountMapping rules`() {
        val specificAccountId = AccountId(50)
        val specificAccount =
            Account(
                id = specificAccountId,
                name = "Specific Vendor",
                openingDate = Clock.System.now(),
            )

        // Persisted mapping for a specific value that would also match the regex rule
        val mapping =
            createAccountMapping(
                id = 1,
                pattern = "^Generic Corp$",
                accountId = specificAccountId,
            )

        val mapper =
            CsvTransferMapper(
                strategy = createStrategyWithRegex(),
                columns = columnsWithType,
                existingAccounts = mapOf("Specific Vendor" to specificAccount),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
                accountMappings = listOf(mapping),
            )

        // This value matches both the persisted mapping AND the regex rule
        val row =
            CsvRow(
                rowIndex = 1,
                values = listOf("15/12/2024", "Payment", "-100.00", "Generic Corp", "Card payment"),
            )
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        // Persisted mapping should take precedence
        assertEquals(specificAccountId, result.transfer.targetAccountId)
    }

    // A strategy whose SOURCE and TARGET both read the same column, with the source assigned a fixed
    // account by an always-match rule (like Crypto.com's card strategy: source = "My Card").
    private fun createStrategySharingDescriptionColumn(): CsvImportStrategy {
        val now = Clock.System.now()
        val base = createStrategyWithRegex()
        return base.copy(
            fieldMappings =
                base.fieldMappings +
                    mapOf(
                        TransferField.SOURCE_ACCOUNT to
                            RegexAccountMapping(
                                id = FieldMappingId(Uuid.random()),
                                fieldType = TransferField.SOURCE_ACCOUNT,
                                columnName = "Description",
                                rules = listOf(RegexRule(pattern = "^", accountName = "My Card")),
                            ),
                        TransferField.TARGET_ACCOUNT to
                            RegexAccountMapping(
                                id = FieldMappingId(Uuid.random()),
                                fieldType = TransferField.TARGET_ACCOUNT,
                                columnName = "Description",
                                rules = emptyList(),
                            ),
                    ),
            updatedAt = now,
        )
    }

    @Test
    fun `persisted counterparty mapping does not hijack the fixed source leg into a self-transfer`() {
        val card = Account(id = AccountId(70), name = "My Card", openingDate = Clock.System.now())
        val vendor = Account(id = AccountId(71), name = "Vendor", openingDate = Clock.System.now())
        // A persisted mapping the user created for the merchant "Amazon" — it matches the row's
        // Description, which BOTH the source (always-match) and target legs read.
        val mapper =
            CsvTransferMapper(
                strategy = createStrategySharingDescriptionColumn(),
                columns = columnsWithType,
                existingAccounts = mapOf("My Card" to card, "Vendor" to vendor),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
                accountMappings = listOf(createAccountMapping(1, "Amazon", vendor.id)),
            )
        val row = CsvRow(rowIndex = 1, values = listOf("15/12/2024", "Amazon purchase", "-10.00", "", ""))

        val result = mapper.mapRow(row)
        assertIs<MappingResult.Success>(result)
        // The counterparty mapping applies to the target; the source falls back to its fixed "My Card".
        assertEquals(card.id, result.transfer.sourceAccountId)
        assertEquals(vendor.id, result.transfer.targetAccountId)
    }

    @Test
    fun `a source re-resolved off a collision is still discovered as a new account`() {
        // No "My Card" account exists yet: the re-resolved source is genuinely new. It must be discovered
        // for creation despite the persisted counterparty mapping that hijacked (and still matches) the row.
        val vendor = Account(id = AccountId(71), name = "Vendor", openingDate = Clock.System.now())
        val mapper =
            CsvTransferMapper(
                strategy = createStrategySharingDescriptionColumn(),
                columns = columnsWithType,
                existingAccounts = mapOf("Vendor" to vendor),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
                accountMappings = listOf(createAccountMapping(1, "Amazon", vendor.id)),
            )
        val row = CsvRow(rowIndex = 1, values = listOf("15/12/2024", "Amazon purchase", "-10.00", "", ""))

        val result = mapper.mapRow(row)
        assertIs<MappingResult.Success>(result)
        assertEquals(vendor.id, result.transfer.targetAccountId)
        assertTrue(result.newAccounts.any { it.name == "My Card" }, "the re-resolved source account must be discovered")
    }

    @Test
    fun `a row that genuinely resolves to one account on both legs is an error row not a crash`() {
        val card = Account(id = AccountId(70), name = "My Card", openingDate = Clock.System.now())
        // The persisted mapping maps the merchant onto the card itself, so even after re-resolving the
        // source there is no distinct counterparty — the row can't be a transfer and must error, not crash.
        val mapper =
            CsvTransferMapper(
                strategy = createStrategySharingDescriptionColumn(),
                columns = columnsWithType,
                existingAccounts = mapOf("My Card" to card),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
                accountMappings = listOf(createAccountMapping(1, "Weird", card.id)),
            )
        val row = CsvRow(rowIndex = 1, values = listOf("15/12/2024", "Weird row", "-10.00", "", ""))

        val result = mapper.mapRow(row)
        assertIs<MappingResult.Error>(result)
    }

    // ============= Column-Agnostic Mappings =============

    @Test
    fun `mapping matches whatever column the strategy reads accounts from`() {
        // Mappings carry no column: the value tested is whatever the strategy's account
        // field-mapping resolves. A global mapping therefore works across strategies that
        // read accounts from differently named columns ("Payee" here, "Name" elsewhere).
        val vendorAccountId = AccountId(40)

        val mapping =
            createAccountMapping(
                id = 1,
                pattern = "^Some Vendor$",
                accountId = vendorAccountId,
            )

        // This strategy reads the target account from the "Payee" column
        val mapper =
            CsvTransferMapper(
                strategy = createStrategy(),
                columns = columns,
                existingAccounts = emptyMap(),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
                accountMappings = listOf(mapping),
            )

        val row =
            CsvRow(
                rowIndex = 1,
                values = listOf("15/12/2024", "Payment", "-100.00", "Some Vendor"),
            )
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals(vendorAccountId, result.transfer.targetAccountId)
        assertNull(result.newAccountName)
    }

    @Test
    fun `same global mapping fires for a strategy reading accounts from a different column`() {
        // The same mapping as above, but under a strategy whose account source is the
        // "Name" column (with "Type" fallback) instead of "Payee".
        val vendorAccountId = AccountId(40)

        val mapping =
            createAccountMapping(
                id = 1,
                pattern = "^Some Vendor$",
                accountId = vendorAccountId,
            )

        val mapper =
            CsvTransferMapper(
                strategy = createStrategyWithRegex(),
                columns = columnsWithType,
                existingAccounts = emptyMap(),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
                accountMappings = listOf(mapping),
            )

        val row =
            CsvRow(
                rowIndex = 1,
                values = listOf("15/12/2024", "Payment", "-100.00", "Some Vendor", "Card payment"),
            )
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals(vendorAccountId, result.transfer.targetAccountId)
        assertNull(result.newAccountName)
    }

    // ============= DiscoveredAccountMapping for Auto-Capture =============

    @Test
    fun `RegexAccountMapping discovered mapping includes matched pattern for auto-capture`() {
        // When a RegexAccountMapping creates a new account via a regex rule,
        // the discoveredMapping should contain:
        // - csvValue: The actual value from the CSV (e.g., "Some generic vendor")
        // - targetAccountName: The extracted account name (e.g., "Generic")
        // - matchedPattern: The regex pattern that matched (e.g., ".*generic.*")
        //
        // This allows auto-capture to create a single mapping using the regex pattern,
        // not multiple exact-match mappings for each CSV value.
        val mapper =
            CsvTransferMapper(
                strategy = createStrategyWithRegex(),
                columns = columnsWithType,
                existingAccounts = emptyMap(),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
                accountMappings = emptyList(),
            )

        // CSV value "Some generic vendor" matches regex rule ".*generic.*" -> "Generic"
        val row =
            CsvRow(
                rowIndex = 1,
                values = listOf("15/12/2024", "Payment", "-100.00", "Some generic vendor", "Card"),
            )
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        // New account should be "Generic" (from the regex rule)
        assertEquals("Generic", result.newAccountName)
        // Discovered mapping should contain enough info for auto-capture
        val discovered = result.discoveredMapping
        // CSV value is "Some generic vendor"
        assertEquals("Some generic vendor", discovered?.csvValue)
        // Target account name should be "Generic" so auto-capture can find the right account
        assertEquals("Generic", discovered?.targetAccountName)
        // matchedPattern should be the regex pattern from the rule
        assertEquals(".*generic.*", discovered?.matchedPattern)
    }

    @Test
    fun `AccountLookupMapping discovered mapping has null matchedPattern`() {
        // For AccountLookupMapping, the csvValue equals the account name,
        // and matchedPattern should be null (exact match should be used for auto-capture)
        val mapper =
            CsvTransferMapper(
                strategy = createStrategy(),
                columns = columns,
                existingAccounts = emptyMap(),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
                accountMappings = emptyList(),
            )

        val row =
            CsvRow(
                rowIndex = 1,
                values = listOf("15/12/2024", "Payment", "-100.00", "New Payee Account"),
            )
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals("New Payee Account", result.newAccountName)
        val discovered = result.discoveredMapping
        assertEquals("New Payee Account", discovered?.csvValue)
        // For AccountLookupMapping, csvValue == targetAccountName
        assertEquals("New Payee Account", discovered?.targetAccountName)
        // matchedPattern should be null for AccountLookupMapping
        assertNull(discovered?.matchedPattern)
    }

    @Test
    fun `RegexAccountMapping fallback has null matchedPattern`() {
        // When no regex rule matches and fallback logic is used,
        // matchedPattern should be null (exact match should be used for auto-capture).
        // The fallback logic uses the first non-blank value from allColumns
        // (primary column + fallback columns).
        val mapper =
            CsvTransferMapper(
                strategy = createStrategyWithRegex(),
                columns = columnsWithType,
                existingAccounts = emptyMap(),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
                accountMappings = emptyList(),
            )

        // CSV value "Special Vendor" doesn't match any regex rule (".*generic.*"),
        // so fallback logic kicks in and uses the first non-blank value from allColumns,
        // which is "Special Vendor" (from the primary "Name" column)
        val row =
            CsvRow(
                rowIndex = 1,
                values = listOf("15/12/2024", "Payment", "-100.00", "Special Vendor", "Card payment"),
            )
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        // Fallback uses first non-blank from allColumns, which is the primary column value
        assertEquals("Special Vendor", result.newAccountName)
        val discovered = result.discoveredMapping
        assertEquals("Special Vendor", discovered?.csvValue)
        // targetAccountName equals the fallback value (same as csvValue in this case)
        assertEquals("Special Vendor", discovered?.targetAccountName)
        // matchedPattern should be null when fallback is used (no regex rule matched)
        assertNull(discovered?.matchedPattern)
    }

    @Test
    fun `RegexAccountMapping fallback to secondary column captures the fallback value`() {
        // When the primary column is blank and fallback to a secondary column is used,
        // the discovered mapping should capture the FALLBACK column's value,
        // not the primary column's.
        val mapper =
            CsvTransferMapper(
                strategy = createStrategyWithRegex(),
                columns = columnsWithType,
                existingAccounts = emptyMap(),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
                accountMappings = emptyList(),
            )

        // Primary "Name" column is blank, so fallback to "Type" column
        val row =
            CsvRow(
                rowIndex = 1,
                values = listOf("15/12/2024", "Payment", "-100.00", "", "Card payment"),
            )
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        // Account name comes from fallback column "Type"
        assertEquals("Card payment", result.newAccountName)
        val discovered = result.discoveredMapping
        // CSV value should be "Card payment" (the value from the fallback column)
        assertEquals("Card payment", discovered?.csvValue)
        assertEquals("Card payment", discovered?.targetAccountName)
        // matchedPattern should be null when fallback is used
        assertNull(discovered?.matchedPattern)
    }

    // ============= Fallback Column Mapping Reuse on Second Import =============

    @Test
    fun `persisted mapping for fallback column is reused on second import`() {
        // Scenario:
        // 1. First import: Primary "Name" is blank, fallback "Type" = "Card payment" → creates account
        // 2. User renames account "Card payment" → "Credit Card Payments"
        // 3. Mapping exists: pattern="^Card payment$", accountId=renamedAccountId
        // 4. Second import: Same CSV with blank "Name" and "Type" = "Card payment"
        // 5. Should reuse the renamed account via the persisted mapping

        val renamedAccountId = AccountId(100)
        val renamedAccount =
            Account(
                id = renamedAccountId,
                name = "Credit Card Payments",
                openingDate = Clock.System.now(),
            )

        // Persisted mapping for the value the fallback column carries
        val mapping =
            createAccountMapping(
                id = 1,
                pattern = "^Card payment$",
                accountId = renamedAccountId,
            )

        val mapper =
            CsvTransferMapper(
                strategy = createStrategyWithRegex(),
                columns = columnsWithType,
                existingAccounts = mapOf("Credit Card Payments" to renamedAccount),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
                accountMappings = listOf(mapping),
            )

        // Second import: Primary "Name" is blank, "Type" = "Card payment"
        val row =
            CsvRow(
                rowIndex = 1,
                values = listOf("15/12/2024", "Payment", "-100.00", "", "Card payment"),
            )
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        // Should use the renamed account via persisted mapping
        assertEquals(renamedAccountId, result.transfer.targetAccountId)
        // No new account should be created
        assertNull(result.newAccountName)
        // No discovered mapping since we used a persisted one
        assertNull(result.discoveredMapping)
    }
}

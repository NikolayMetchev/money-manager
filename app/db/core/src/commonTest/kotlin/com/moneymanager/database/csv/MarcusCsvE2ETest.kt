package com.moneymanager.database.csv

import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.csvimporter.AttributeAccountMatcher
import com.moneymanager.csvimporter.applyStagedCsv
import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.csvimporter.stageQifImport
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.rules.SettlementWindow
import com.moneymanager.domain.model.rules.ValueExpr
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createAccount
import com.moneymanager.test.database.createOwnership
import com.moneymanager.test.database.createPerson
import com.moneymanager.test.database.reimports
import com.moneymanager.test.database.addAccountAttributes
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.uuid.Uuid

/**
 * End-to-end cover for the built-in Marcus strategy: rows shaped like Marcus's savings export go through the real
 * engine. Marcus names both ends by account number only, so an account carrying that number (as a bank feed such as
 * Monzo's API leaves it) is the account a row books against.
 */
class MarcusCsvE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val now = Clock.System.now()

    private val headers = listOf("TransactionDate", "Description", "Value", "AccountBalance", "AccountName", "AccountNumber")

    private val maintenance =
        object : Maintenance {
            override suspend fun reindex(): Duration = Duration.ZERO

            override suspend fun vacuum(): Duration = Duration.ZERO

            override suspend fun analyze(): Duration = Duration.ZERO

            override suspend fun refreshMaterializedViews(): Duration = Duration.ZERO

            override suspend fun fullRefreshMaterializedViews(): Duration = Duration.ZERO
        }

    private fun row(
        date: String,
        description: String,
        value: String,
        balance: String,
    ) = listOf(date, description, value, balance, " Main Account", MARCUS_NUMBER)

    private val deposit = row("20200505", "Transfer from $BANK_NUMBER", "22.50", "122.50")
    private val withdrawal = row("20200506", "Withdrawal to $BANK_NUMBER", "-20.00", "102.50")
    private val interest = row("20200502", "Interest applied", "33.12", "100.00")

    private suspend fun stage(
        rows: List<List<String>>,
        fileName: String = "Transactions $MARCUS_NUMBER 2020-05-10 11_04.csv",
    ): CsvImport {
        val id =
            repositories.csvImportRepository.createImport(
                fileName = fileName,
                headers = headers,
                rows = rows,
                fileChecksum = "checksum-$fileName-${rows.hashCode()}",
                fileLastModified = now,
            )
        return repositories.csvImportRepository.getImport(id).first()!!
    }

    private suspend fun apply(imports: List<CsvImport>) =
        bulkApplyCsv(
            imports = imports,
            sourceAccountOverride = null,
            strategies = repositories.csvImportStrategyRepository.getAllStrategies().first(),
            currencies = repositories.currencyRepository.getAllCurrencies().first(),
            accountMappingRepository = repositories.accountMappingRepository,
            accountRepository = repositories.accountRepository,
            csvImportRepository = repositories.csvImportRepository,
            maintenance = maintenance,
            importEngine = repositories.importEngine,
            onProgress = { },
            cryptoRepository = repositories.cryptoRepository,
            attributeAccountMatchers = matchers(),
            tradeRepository = repositories.tradeRepository,
        )

    private suspend fun accounts(): List<Account> = repositories.accountRepository.getAllAccounts().first()

    /** Every GBP balance, by account name, as text. */
    private suspend fun balances(): Map<String, String> {
        repositories.maintenanceService.refreshMaterializedViews()
        val names = accounts().associate { it.id to it.name }
        return repositories.transactionRepository
            .getAccountBalances()
            .first()
            .filter { it.balance.amount != BigInteger.ZERO }
            .associate {
                names.getValue(it.accountId) to
                    it.balance
                        .toDisplayValue()
                        .toString()
            }
    }

    /** An account carrying [number] as its account number, as a bank feed names the accounts it pays. */
    private suspend fun numberedAccount(
        name: String,
        number: String,
    ): AccountId {
        val id = repositories.accountRepository.createAccount(Account(id = AccountId(0), name = name, openingDate = now))
        repositories.accountRepository.addAccountAttributes(
            accountId = id,
            newAttributes = listOf(NewAttribute(AttributeTypeId(WellKnownIds.ACCOUNT_ACCOUNT_NUMBER_ATTR_TYPE_ID), number)),
        )
        return id
    }

    @Test
    fun `an export naming numbers no account carries books against accounts of its own`() =
        runDbTest {
            val result = apply(listOf(stage(listOf(withdrawal, deposit, interest))))
            assertEquals(1, result.filesImported)

            assertEquals(
                mapOf(
                    "Marcus" to "35.62",
                    "Account $BANK_NUMBER" to "-2.5",
                    "Marcus Interest" to "-33.12",
                ),
                balances(),
            )
        }

    @Test
    fun `both ends resolve to the accounts carrying their numbers`() =
        runDbTest {
            numberedAccount("Alex Smith (1234)", MARCUS_NUMBER)
            numberedAccount("Santander (5678)", BANK_NUMBER)

            apply(listOf(stage(listOf(withdrawal, deposit))))

            assertEquals(mapOf("Alex Smith (1234)" to "2.5", "Santander (5678)" to "-2.5"), balances())
            assertNull(accounts().firstOrNull { it.name == "Marcus" })
        }

    @Test
    fun `a transfer the bank feed already records counts once`() =
        runDbTest {
            val marcus = numberedAccount("Alex Smith (1234)", MARCUS_NUMBER)
            val bank = numberedAccount("Monzo", BANK_NUMBER)
            applyBankFeed(from = bank, to = marcus)

            apply(listOf(stage(listOf(deposit))))

            assertEquals(mapOf("Alex Smith (1234)" to "22.5", "Monzo" to "-22.5"), balances())
        }

    @Test
    fun `a transfer the bank feed records later counts once`() =
        runDbTest {
            val marcus = numberedAccount("Alex Smith (1234)", MARCUS_NUMBER)
            val bank = numberedAccount("Monzo", BANK_NUMBER)
            apply(listOf(stage(listOf(deposit))))

            applyBankFeed(from = bank, to = marcus)

            assertEquals(mapOf("Alex Smith (1234)" to "22.5", "Monzo" to "-22.5"), balances())
        }

    @Test
    fun `equal transfers on one day are both kept`() =
        runDbTest {
            val second = row("20200505", "Transfer from $BANK_NUMBER", "22.50", "145.00")
            apply(listOf(stage(listOf(second, deposit))))

            assertEquals(mapOf("Marcus" to "45", "Account $BANK_NUMBER" to "-45"), balances())
        }

    @Test
    fun `a later export overlapping an earlier one adds only what is new`() =
        runDbTest {
            apply(listOf(stage(listOf(deposit, interest), fileName = "20190202-20200505.csv")))
            apply(listOf(stage(listOf(withdrawal, deposit, interest))))

            assertEquals(
                mapOf(
                    "Marcus" to "35.62",
                    "Account $BANK_NUMBER" to "-2.5",
                    "Marcus Interest" to "-33.12",
                ),
                balances(),
            )
        }

    @Test
    fun `re-importing changes nothing`() =
        runDbTest {
            val marcus = numberedAccount("Alex Smith (1234)", MARCUS_NUMBER)
            val bank = numberedAccount("Monzo", BANK_NUMBER)
            applyBankFeed(from = bank, to = marcus)
            apply(listOf(stage(listOf(withdrawal, deposit, interest))))
            val before = balances()

            val reimports = repositories.reimports(maintenance)
            reimports.reimportAll(
                reimports.planAll(repositories.csvImportRepository.getAllImports().first()),
                accountId = null,
                currencyId = null,
                onProgress = {},
            )

            assertEquals(before, balances())
        }

    /**
     * Santander's account, owned by its holder, and the Santander statement's receipt "FROM <holder>" on it. The
     * statement names the payer only by the holder's own name, so it doesn't know which of their accounts the
     * money came from.
     */
    private suspend fun santanderReceipt(
        santander: AccountId,
        payer: String,
    ) {
        val id =
            repositories.importEngine.stageQifImport(
                fileName = "santander.qif",
                content = "!Type:Bank\nD06/05/2020\nT20.00\nPFASTER PAYMENTS RECEIPT  FROM $payer, 20.00\n^\n",
                fileChecksum = "santander-$payer",
                fileLastModified = now,
            )
        bulkApplyCsv(
            imports = listOf(repositories.csvImportRepository.getImport(id).first()!!),
            sourceAccountOverride = santander,
            strategies = repositories.csvImportStrategyRepository.getAllStrategies().first(),
            currencies = repositories.currencyRepository.getAllCurrencies().first(),
            accountMappingRepository = repositories.accountMappingRepository,
            accountRepository = repositories.accountRepository,
            csvImportRepository = repositories.csvImportRepository,
            maintenance = maintenance,
            importEngine = repositories.importEngine,
            onProgress = { },
            attributeAccountMatchers = matchers(),
        )
    }

    /** Santander's account, carrying its number, owned by its holder. */
    private suspend fun santanderHeldByItsHolder(): AccountId {
        val holder =
            repositories.personRepository.createPerson(
                Person(PersonId(0), firstName = "ALEX", middleName = null, lastName = "SMITH"),
            )
        return numberedAccount("Santander", BANK_NUMBER).also { repositories.personAccountOwnershipRepository.createOwnership(holder, it) }
    }

    private val marcusWithdrawal = row("20200506", "Withdrawal to $BANK_NUMBER", "-20.00", "80.00")

    @Test
    fun `a receipt from the account holder is the other side of a Marcus withdrawal`() =
        runDbTest {
            val santander = santanderHeldByItsHolder()
            numberedAccount("Marcus (1234)", MARCUS_NUMBER)
            santanderReceipt(santander, "ALEX SMITH")

            apply(listOf(stage(listOf(marcusWithdrawal))))

            assertEquals(mapOf("Santander" to "20", "Marcus (1234)" to "-20"), balances())
        }

    @Test
    fun `a Marcus withdrawal is the other side of a later receipt from the account holder`() =
        runDbTest {
            val santander = santanderHeldByItsHolder()
            numberedAccount("Marcus (1234)", MARCUS_NUMBER)
            apply(listOf(stage(listOf(marcusWithdrawal))))

            santanderReceipt(santander, "Alex  Smith")

            assertEquals(mapOf("Santander" to "20", "Marcus (1234)" to "-20"), balances())
        }

    @Test
    fun `a receipt from someone else is not a Marcus withdrawal`() =
        runDbTest {
            val santander = santanderHeldByItsHolder()
            numberedAccount("Marcus (1234)", MARCUS_NUMBER)
            santanderReceipt(santander, "JANE DOE")

            apply(listOf(stage(listOf(marcusWithdrawal))))

            assertEquals(mapOf("Santander" to "40", "Marcus (1234)" to "-20", "JANE DOE" to "-20"), balances())
        }

    /** A bank feed's record of a £22.50 payment [from] → [to] on 4 May 2020, the way a feed that knows both ends books it. */
    private suspend fun applyBankFeed(
        from: AccountId,
        to: AccountId,
    ) {
        val feedHeaders = listOf("Date", "Amount", "Currency", "Description")
        val feed =
            CsvImportStrategy(
                id = CsvImportStrategyId(Uuid.random()),
                name = "Bank feed",
                config =
                    CsvStrategyConfig(
                        identificationColumns = feedHeaders.toSet(),
                        crossSourceReconcileWindow = SettlementWindow.Elapsed(BANK_RECONCILE_WINDOW_SECONDS),
                        fieldMappings =
                            mapOf(
                                TransferField.TIMESTAMP to
                                    DateTimeParsingMapping(TransferField.TIMESTAMP, dateColumnName = "Date", dateFormat = "yyyy-MM-dd"),
                                TransferField.SOURCE_ACCOUNT to HardCodedAccountMapping(TransferField.SOURCE_ACCOUNT, from),
                                TransferField.TARGET_ACCOUNT to HardCodedAccountMapping(TransferField.TARGET_ACCOUNT, to),
                                TransferField.DESCRIPTION to
                                    DirectColumnMapping(TransferField.DESCRIPTION, value = ValueExpr(listOf("Description"))),
                                TransferField.AMOUNT to
                                    AmountParsingMapping(TransferField.AMOUNT, AmountMode.SINGLE_COLUMN, amountColumnName = "Amount"),
                                TransferField.CURRENCY to
                                    CurrencyLookupMapping(TransferField.CURRENCY, value = ValueExpr(listOf("Currency"))),
                                TransferField.TIMEZONE to HardCodedTimezoneMapping(TransferField.TIMEZONE, "UTC"),
                            ),
                    ),
                createdAt = now,
                updatedAt = now,
            )
        repositories.csvImportStrategyRepository.createStrategy(feed, Source.Manual)
        val feedImportId =
            repositories.csvImportRepository.createImport(
                fileName = "bank-feed.csv",
                headers = feedHeaders,
                rows = listOf(listOf("2020-05-04", "-22.50", "GBP", "Alex Smith")),
                fileChecksum = "bank-feed",
                fileLastModified = now,
            )
        applyStagedCsv(
            csvImport = repositories.csvImportRepository.getImport(feedImportId).first()!!,
            strategy = feed,
            sourceAccountOverride = null,
            currencies = repositories.currencyRepository.getAllCurrencies().first(),
            accountMappingRepository = repositories.accountMappingRepository,
            accountRepository = repositories.accountRepository,
            csvImportRepository = repositories.csvImportRepository,
            maintenance = maintenance,
            importEngine = repositories.importEngine,
            refreshViews = false,
            cryptoRepository = repositories.cryptoRepository,
            attributeAccountMatchers = matchers(),
        )
    }

    /** Every account attribute as the import screens hand it over, so a row can resolve an account by its number. */
    private suspend fun matchers() = AttributeAccountMatcher.registry(repositories.accountAttributeRepository.getAll().first())

    private companion object {
        const val MARCUS_NUMBER = "40001234"
        const val BANK_NUMBER = "40005678"
        const val BANK_RECONCILE_WINDOW_SECONDS = 172_800L
    }
}

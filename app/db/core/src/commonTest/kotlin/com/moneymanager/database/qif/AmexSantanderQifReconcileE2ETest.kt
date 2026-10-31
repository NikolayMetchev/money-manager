package com.moneymanager.database.qif

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.csvimporter.CsvImportResult
import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.csvimporter.stageQifImport
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.calendar.testing.TestHolidayCalendars
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportOperation
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.SourceRerunResult
import com.moneymanager.importengineapi.setDefaultLocation
import com.moneymanager.importengineapi.upsertHolidayCalendar
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createAccount
import com.moneymanager.test.database.installBuiltInCsvStrategies
import com.moneymanager.test.database.reimports
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * A card payment recorded by both the card's statement and the paying bank's is counted once. American
 * Express records the payment as "PAYMENT RECEIVED - THANK YOU", which names no payer; Santander records
 * the same money as its direct debit to the card, naming both ends. The Amex row's payer is a placeholder
 * that reconciles against the bank's record (same card, direction and amount, within the statement's
 * 3-business-day placeholder window), in either import order, and the bank's record is the one kept.
 */
class AmexSantanderQifReconcileE2ETest : DbTest() {
    private val now = Clock.System.now()

    private val maintenance =
        object : Maintenance {
            override suspend fun reindex(): Duration = Duration.ZERO

            override suspend fun vacuum(): Duration = Duration.ZERO

            override suspend fun analyze(): Duration = Duration.ZERO

            override suspend fun refreshMaterializedViews(): Duration = Duration.ZERO

            override suspend fun fullRefreshMaterializedViews(): Duration = Duration.ZERO
        }

    /** The 2020 payments: (Amex date, Santander direct debit date, amount). The last is three days apart. */
    private var payments =
        listOf(
            Triple("02/02/2020", "04/02/2020", "1239.68"),
            Triple("04/03/2020", "04/03/2020", "738.42"),
            Triple("02/04/2020", "02/04/2020", "1574.62"),
            Triple("03/05/2020", "06/05/2020", "1801.13"),
        )

    private suspend fun stage(
        fileName: String,
        records: List<String>,
    ): CsvImport {
        val id =
            repositories.importEngine.stageQifImport(
                fileName = fileName,
                content = "!Type:Bank\n" + records.joinToString(""),
                fileChecksum = "$fileName-checksum",
                fileLastModified = now,
            )
        return repositories.csvImportRepository.getImport(id).first()!!
    }

    private fun record(
        date: String,
        amount: String,
        payee: String,
        memo: String? = null,
        checkNumber: String? = null,
    ) = buildString {
        append("D$date\nT$amount\nP$payee\n")
        memo?.let { append("M$it\n") }
        checkNumber?.let { append("N$it\n") }
        append("^\n")
    }

    private suspend fun amexStatement(
        fileName: String = "amex.qif",
        referencePrefix: String = "9936181",
    ): CsvImport =
        stage(
            fileName,
            payments.mapIndexed { i, (date, _, amount) ->
                record(date, amount, "PAYMENT RECEIVED - THANK YOU", memo = "  Process Date $date", checkNumber = "$referencePrefix$i")
            } +
                record(
                    "19/03/2020",
                    "-16.10",
                    "SAINSBURY'S",
                    memo = " GOODS Process Date 19/03/2020",
                    checkNumber = "${referencePrefix}9",
                ),
        )

    private suspend fun santanderStatement(): CsvImport =
        stage(
            "santander.qif",
            payments.map { (_, date, amount) ->
                record(
                    date,
                    "-$amount",
                    "DIRECT DEBIT PAYMENT TO AMERICAN EXPRESS REF 3746-935125-04002, MANDATE NO 0013, $amount",
                )
            },
        )

    private suspend fun accountId(name: String): AccountId =
        repositories.accountRepository
            .getAllAccounts()
            .first()
            .firstOrNull { it.name == name }
            ?.id
            ?: repositories.accountRepository.createAccount(Account(id = AccountId(0), name = name, openingDate = now))

    private suspend fun apply(
        qifImport: CsvImport,
        accountName: String,
    ) {
        val result =
            bulkApplyCsv(
                imports = listOf(qifImport),
                sourceAccountOverride = accountId(accountName),
                strategies = repositories.csvImportStrategyRepository.getAllStrategies().first(),
                currencies = repositories.currencyRepository.getAllCurrencies().first(),
                accountMappingRepository = repositories.accountMappingRepository,
                accountRepository = repositories.accountRepository,
                csvImportRepository = repositories.csvImportRepository,
                maintenance = maintenance,
                importEngine = repositories.importEngine,
                onProgress = { },
            )
        assertEquals(1, result.filesImported, "$accountName: ${result.files}")
    }

    private suspend fun assertEachPaymentCountedOnceFromTheBank(
        cardRecords: Int = 1,
        step: String = "",
    ) {
        val amexId = accountId(AMEX)
        val names =
            repositories.accountRepository
                .getAllAccounts()
                .first()
                .associate { it.id to it.name }
        val intoCard =
            repositories.transactionRepository
                .getTransactionsByAccount(amexId)
                .first()
                .filter { it.targetAccountId == amexId }
        for ((_, _, amount) in payments) {
            // Each record of the payment, as (payer account, excluded?).
            val forPayment =
                intoCard
                    .filter { it.amount.toDisplayValue().compareTo(BigDecimal(amount)) == 0 }
                    .map { transfer ->
                        names.getValue(transfer.sourceAccountId) to transfer.attributes.any { it.attributeType.name == "excluded" }
                    }
            assertEquals(1 + cardRecords, forPayment.size, "$step£$amount: every statement's record is kept: $forPayment")
            assertEquals(
                listOf(SANTANDER),
                forPayment.filterNot { it.second }.map { it.first },
                "$step£$amount: only the bank's record, which names the payer, is counted: $forPayment",
            )
        }
    }

    @Test
    fun bankFirst_thenCard_excludesTheCardsPlaceholderRecords() =
        runDbTest {
            repositories.installBuiltInCsvStrategies()
            accountId(AMEX)
            apply(santanderStatement(), SANTANDER)
            apply(amexStatement(), AMEX)
            assertEachPaymentCountedOnceFromTheBank()
        }

    @Test
    fun cardFirst_thenBank_excludesTheCardsPlaceholderRecords() =
        runDbTest {
            repositories.installBuiltInCsvStrategies()
            accountId(AMEX)
            apply(amexStatement(), AMEX)
            apply(santanderStatement(), SANTANDER)
            assertEachPaymentCountedOnceFromTheBank()
        }

    /** Santander's statement, then two statements of the card that each record every payment (the QIF's and a PDF's, say). */
    private suspend fun importBankAndBothCardStatements(): Pair<CsvImport, CsvImport> {
        repositories.installBuiltInCsvStrategies()
        accountId(AMEX)
        apply(santanderStatement(), SANTANDER)
        val card = amexStatement()
        apply(card, AMEX)
        val secondCard = amexStatement("amex-pdf.qif", referencePrefix = "8836181")
        apply(secondCard, AMEX)
        assertEachPaymentCountedOnceFromTheBank(cardRecords = 2, step = "Before the re-import: ")
        return card to secondCard
    }

    private suspend fun reimport(file: CsvImport): SourceRerunResult<CsvImportResult?> {
        val reimports = repositories.reimports(maintenance)
        return reimports.reimport(
            reimports.plan(repositories.csvImportRepository.getImport(file.id).first()!!),
            accountId = accountId(AMEX),
            currencyId = null,
        )
    }

    // Two equal purchases from one shop two days apart, the earlier posted onto the next month's statement: two lines
    // of the card's statements, each with its own reference, never two sources' records of one purchase.
    @Test
    fun equalPurchasesOnTwoStatements_bothCounted() =
        runDbTest {
            repositories.installBuiltInCsvStrategies()
            val amexId = accountId(AMEX)
            val purchase = { date: String, reference: String ->
                record(date, "-2.35", "TESCO STORES", memo = " GOODS Process Date $date", checkNumber = reference)
            }
            apply(stage("amex-october.qif", listOf(purchase("08/10/2008", "1001"))), AMEX)
            apply(stage("amex-november.qif", listOf(purchase("06/10/2008", "2001"))), AMEX)

            val purchases =
                repositories.transactionRepository
                    .getTransactionsByAccount(amexId)
                    .first()
                    .filter { it.sourceAccountId == amexId }
            assertEquals(2, purchases.size, "$purchases")
            assertEquals(
                emptyList(),
                purchases.filter { t -> t.attributes.any { it.attributeType.name == "excluded" } },
                "neither purchase is reconciled away against the other",
            )
        }

    // The second statement's placeholders reconciled against the first's, which lost to the bank's records. Deleting
    // the first statement's would leave them counted, as its re-run never looks at them: they are re-run after it.
    @Test
    fun reimportingTheFirstCardStatement_reconcilesTheSecondsPlaceholdersAgain() =
        runDbTest {
            val (card, _) = importBankAndBothCardStatements()
            reimport(card)
            assertEachPaymentCountedOnceFromTheBank(cardRecords = 2, step = "After re-importing the first statement: ")
        }

    @Test
    fun reimportingTheSecondCardStatement_reconcilesItsPlaceholdersAgain() =
        runDbTest {
            val (_, secondCard) = importBankAndBothCardStatements()
            reimport(secondCard)
            assertEachPaymentCountedOnceFromTheBank(cardRecords = 2, step = "After re-importing the second statement: ")
        }

    @Test
    fun reimportingTheFirstCardStatement_keepsHandEditsOfTheSecondsPlaceholders() =
        runDbTest {
            val (card, secondCard) = importBankAndBothCardStatements()
            val placeholder =
                repositories.transferSourceRepository
                    .getTransfersCreatedBy(Source.Csv(secondCard.id))
                    .keys
                    .let { repositories.transactionRepository.getTransactionsByIds(it).values }
                    .first { it.description.startsWith("PAYMENT RECEIVED") }
            repositories.importEngine.import(
                ImportBatch.manualEdits(
                    transfers =
                        listOf(
                            ImportTransfer(
                                source = Source.Manual,
                                operation = ImportOperation.UPDATE,
                                existingId = placeholder.id,
                                fromAccount = AccountRef.Existing(placeholder.sourceAccountId),
                                toAccount = AccountRef.Existing(placeholder.targetAccountId),
                                timestamp = placeholder.timestamp,
                                description = "Paid from Santander",
                                amount = placeholder.amount,
                            ),
                        ),
                ),
            )

            val result = reimport(card)

            assertEquals(listOf("Paid from Santander"), result.reappliedEdits.map { it.description }, "unmatched: ${result.unmatchedEdits}")
            val recreated =
                repositories.transferSourceRepository
                    .getTransfersCreatedBy(Source.Csv(secondCard.id))
                    .keys
                    .let { repositories.transactionRepository.getTransactionsByIds(it).values }
            assertEquals(1, recreated.count { it.description == "Paid from Santander" && it.amount == placeholder.amount })
            assertEachPaymentCountedOnceFromTheBank(cardRecords = 2, step = "After re-importing the first statement: ")
        }

    // Amex books Santander's Maundy Thursday payment on Easter Monday: four calendar days, but one London business
    // day (Good Friday and Easter Monday are bank holidays), so the statements' 3-business-day window still pairs them.
    private val easter2021 = listOf(Triple("05/04/2021", "01/04/2021", "2087.67"))

    // The same amount a week later is a different payment: five business days apart, outside the window.
    private val weekApart = listOf(Triple("22/04/2021", "15/04/2021", "2087.67"))

    private suspend fun atLondon(paymentList: List<Triple<String, String, String>>) {
        payments = paymentList
        repositories.installBuiltInCsvStrategies()
        repositories.importEngine.upsertHolidayCalendar(TestHolidayCalendars.LONDON)
        repositories.importEngine.setDefaultLocation("GBLO")
        accountId(AMEX)
    }

    @Test
    fun bankFirst_reconcilesAPaymentAcrossTheEasterWeekend() =
        runDbTest {
            atLondon(easter2021)
            apply(santanderStatement(), SANTANDER)
            apply(amexStatement(), AMEX)
            assertEachPaymentCountedOnceFromTheBank()
        }

    @Test
    fun cardFirst_reconcilesAPaymentAcrossTheEasterWeekend() =
        runDbTest {
            atLondon(easter2021)
            apply(amexStatement(), AMEX)
            apply(santanderStatement(), SANTANDER)
            assertEachPaymentCountedOnceFromTheBank()
        }

    @Test
    fun theSameAmountAWeekApart_isNotReconciled() =
        runDbTest {
            atLondon(weekApart)
            apply(santanderStatement(), SANTANDER)
            apply(amexStatement(), AMEX)
            val amexId = accountId(AMEX)
            val counted =
                repositories.transactionRepository
                    .getTransactionsByAccount(amexId)
                    .first()
                    .filter { it.targetAccountId == amexId && it.amount.toDisplayValue().compareTo(BigDecimal("2087.67")) == 0 }
                    .filterNot { transfer -> transfer.attributes.any { it.attributeType.name == "excluded" } }
            assertEquals(2, counted.size, "both records stay counted: $counted")
        }

    private companion object {
        // The card account under the name Santander's payee resolves to, so both statements book onto it.
        const val AMEX = "AMERICAN EXPRESS"
        const val SANTANDER = "Santander"
    }
}

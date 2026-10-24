package com.moneymanager.database.qif

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.qif.QifImport
import com.moneymanager.domain.model.qif.QifImportRecord
import com.moneymanager.qifimporter.bulkApplyQif
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createAccount
import com.moneymanager.test.database.installBuiltInCsvStrategies
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * A card payment recorded by both the card's statement and the paying bank's is counted once. American
 * Express records the payment as "PAYMENT RECEIVED - THANK YOU", which names no payer; Santander records
 * the same money as its direct debit to the card, naming both ends. The Amex row's payer is a placeholder
 * that reconciles against the bank's record (same card, direction and amount, within the engine's date
 * tolerance), in either import order, and the bank's record is the one kept.
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
    private val payments =
        listOf(
            Triple("02/02/2020", "04/02/2020", "1239.68"),
            Triple("04/03/2020", "04/03/2020", "738.42"),
            Triple("02/04/2020", "02/04/2020", "1574.62"),
            Triple("03/05/2020", "06/05/2020", "1801.13"),
        )

    private suspend fun stage(
        fileName: String,
        accountName: String,
        records: List<QifImportRecord>,
    ): QifImport {
        val id =
            repositories.qifImportRepository.createImport(
                fileName = fileName,
                records = records.map { it.copy(accountName = accountName) },
                accountType = "BANK",
                fileChecksum = "$fileName-checksum",
                fileLastModified = now,
            )
        return repositories.qifImportRepository.getImport(id).first()!!
    }

    private fun record(
        index: Int,
        date: String,
        amount: String,
        payee: String,
        memo: String? = null,
        checkNumber: String? = null,
    ) = QifImportRecord(
        recordIndex = index.toLong(),
        sectionType = "BANK",
        accountName = null,
        supported = true,
        rawText = "",
        date = date,
        amount = amount,
        payee = payee,
        memo = memo,
        checkNumber = checkNumber,
    )

    private suspend fun amexStatement(): QifImport =
        stage(
            "amex.qif",
            AMEX,
            payments.mapIndexed { i, (date, _, amount) ->
                record(i, date, amount, "PAYMENT RECEIVED - THANK YOU", memo = "  Process Date $date", checkNumber = "9936181$i")
            } +
                record(
                    payments.size,
                    "19/03/2020",
                    "-16.10",
                    "SAINSBURY'S",
                    memo = " GOODS Process Date 19/03/2020",
                    checkNumber = "10698290",
                ),
        )

    private suspend fun santanderStatement(): QifImport =
        stage(
            "santander.qif",
            SANTANDER,
            payments.mapIndexed { i, (_, date, amount) ->
                record(
                    i,
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
        qifImport: QifImport,
        accountName: String,
    ) {
        val result =
            bulkApplyQif(
                imports = listOf(qifImport),
                sourceAccountId = accountId(accountName),
                strategies = repositories.csvImportStrategyRepository.getAllStrategies().first(),
                currencies = repositories.currencyRepository.getAllCurrencies().first(),
                accountMappingRepository = repositories.accountMappingRepository,
                accountRepository = repositories.accountRepository,
                qifImportRepository = repositories.qifImportRepository,
                maintenance = maintenance,
                importEngine = repositories.importEngine,
                onProgress = { },
            )
        assertEquals(1, result.filesImported, "$accountName: ${result.files}")
    }

    private suspend fun assertEachPaymentCountedOnceFromTheBank() {
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
            assertEquals(2, forPayment.size, "£$amount: both statements' records are kept: $forPayment")
            assertEquals(
                listOf(SANTANDER),
                forPayment.filterNot { it.second }.map { it.first },
                "£$amount: only the bank's record, which names the payer, is counted: $forPayment",
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

    private companion object {
        // The card account under the name Santander's payee resolves to, so both statements book onto it.
        const val AMEX = "AMERICAN EXPRESS"
        const val SANTANDER = "Santander"
    }
}

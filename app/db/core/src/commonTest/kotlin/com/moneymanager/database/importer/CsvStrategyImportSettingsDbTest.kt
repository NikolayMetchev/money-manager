package com.moneymanager.database.importer

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.FieldMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.importengineapi.createCsvStrategy
import com.moneymanager.importengineapi.updateCsvStrategy
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createAccount
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The files a strategy imported re-import with its source account and currency, so no version of it may stop setting them. */
class CsvStrategyImportSettingsDbTest : DbTest() {
    private val now = Instant.fromEpochMilliseconds(1_000)

    private fun strategy(vararg mappings: FieldMapping) =
        CsvImportStrategy(
            id = CsvImportStrategyId(Uuid.random()),
            name = "Bank",
            config = CsvStrategyConfig(identificationColumns = setOf("Date"), fieldMappings = mappings.associateBy { it.fieldType }),
            createdAt = now,
            updatedAt = now,
        )

    @Test
    fun aStrategyCanNotStopSettingItsSourceAccountOrCurrency() =
        runDbTest {
            val account = repositories.accountRepository.createAccount(Account(id = AccountId(0), name = "Bank", openingDate = now))
            val usd = assertNotNull(repositories.currencyRepository.getCurrencyByCode("USD").first()).id
            val source = HardCodedAccountMapping(TransferField.SOURCE_ACCOUNT, account)
            val currency = HardCodedCurrencyMapping(TransferField.CURRENCY, usd)
            val unsaved = strategy(source, currency)
            val saved = unsaved.copy(id = repositories.importEngine.createCsvStrategy(unsaved))

            for (dropped in listOf(saved.withMappings(currency), saved.withMappings(source))) {
                val refused = assertFails { repositories.importEngine.updateCsvStrategy(dropped) }
                assertContains(refused.message.orEmpty(), "can't stop setting its source account or currency")
            }

            // Another account is still an account: the files re-import onto the one it names now.
            val other = repositories.accountRepository.createAccount(Account(id = AccountId(0), name = "Other", openingDate = now))
            repositories.importEngine.updateCsvStrategy(
                saved.withMappings(HardCodedAccountMapping(TransferField.SOURCE_ACCOUNT, other), currency),
            )
            val stored = assertNotNull(repositories.csvImportStrategyRepository.getStrategyById(saved.id).first())
            assertEquals(
                HardCodedAccountMapping(TransferField.SOURCE_ACCOUNT, other),
                stored.config.fieldMappings[TransferField.SOURCE_ACCOUNT],
            )

            // A strategy that never set them may start to.
            val unset = strategy().copy(name = "Card")
            val card = unset.copy(id = repositories.importEngine.createCsvStrategy(unset))
            repositories.importEngine.updateCsvStrategy(card.withMappings(source))
        }

    private fun CsvImportStrategy.withMappings(vararg mappings: FieldMapping) =
        copy(config = config.copy(fieldMappings = mappings.associateBy { it.fieldType }))
}

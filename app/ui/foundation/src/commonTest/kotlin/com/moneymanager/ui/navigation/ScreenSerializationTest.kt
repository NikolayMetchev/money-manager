package com.moneymanager.ui.navigation

import androidx.navigation3.runtime.NavKey
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.ImportDirectoryId
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.QifImportId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.strategy.StrategyKind
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * Round-trips one instance of every [Screen] subtype through the polymorphic [NavKey] serializer
 * that `rememberNavBackStack` uses, so an unserializable destination (e.g. a new id type missing
 * `@Serializable`) fails here instead of at runtime when the back stack is saved.
 */
class ScreenSerializationTest {
    private val json = Json { serializersModule = ScreenSavedStateConfiguration.serializersModule }

    private val uuid = Uuid.parse("0b3a4e6e-8f5d-4c1a-9e2b-7d6c5b4a3f21")

    private val screens: List<Screen> =
        listOf(
            Screen.Accounts(scrollToAccountId = AccountId(1)),
            Screen.Currencies,
            Screen.Categories,
            Screen.People,
            Screen.PeopleScroll(PersonId(2)),
            Screen.Imports(ImportTab.QIF),
            Screen.CsvStrategies,
            Screen.CsvStrategyEditor(CsvImportId(uuid), CsvImportStrategyId(uuid)),
            Screen.ApiStrategies,
            Screen.StrategyCatalog(StrategyKind.PASS_THROUGH),
            Screen.ApiStrategyEditor(ApiImportStrategyId(uuid)),
            Screen.Settings,
            Screen.DatabaseSizeBreakdown,
            Screen.AccountTransactions(AccountId(3), "Checking", TransferId(4), CurrencyId(5)),
            Screen.CsvImportDetail(CsvImportId(uuid), scrollToRowIndex = 7),
            Screen.QifImportDetail(QifImportId(uuid), scrollToRecordIndex = 8),
            Screen.QifStrategyEditor(QifImportId(uuid), CsvImportStrategyId(uuid)),
            Screen.AuditHistory(TransferId(9)),
            Screen.AccountAuditHistory(AccountId(10), "Savings"),
            Screen.PersonAuditHistory(PersonId(11), "Ada"),
            Screen.CurrencyAuditHistory(CurrencyId(12), "GBP"),
            Screen.CategoryAuditHistory(13, "Groceries"),
            Screen.ApiStrategyAuditHistory(ApiImportStrategyId(uuid), "Monzo"),
            Screen.CsvStrategyAuditHistory(CsvImportStrategyId(uuid), "Wise"),
            Screen.ImportDirectoryAuditHistory(ImportDirectoryId(uuid), "Downloads"),
            Screen.ConnectApi,
            Screen.ApiSessionTraffic(ApiSessionId(14), ApiRequestId(15), "$.items[0]"),
        )

    @Test
    fun everyScreenSubtypeRoundTripsThroughTheNavKeySerializer() {
        val serializer = PolymorphicSerializer(NavKey::class)
        for (screen in screens) {
            val encoded = json.encodeToString(serializer, screen)
            val decoded = json.decodeFromString(serializer, encoded)
            assertEquals(screen, decoded, "Round trip failed for ${screen::class.simpleName}")
        }
    }
}

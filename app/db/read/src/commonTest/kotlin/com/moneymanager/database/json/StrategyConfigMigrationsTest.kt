package com.moneymanager.database.json

import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.ConditionalAccountMapping
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.rules.AssetCodeRules
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.Extraction
import com.moneymanager.domain.model.rules.ValueExpr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class StrategyConfigMigrationsTest {
    @Test
    fun `stamping writes the current version and upgrading strips it`() {
        val stamped = StrategyConfigMigrations.stampCsv(JsonObject(mapOf("fileNamePattern" to JsonPrimitive("x"))))
        assertEquals(StrategyConfigMigrations.currentCsvVersion, stamped.getValue(StrategyConfigMigrations.VERSION_KEY).jsonPrimitive.int)

        val upgraded = StrategyConfigMigrations.upgradeCsv(stamped)
        assertFalse(StrategyConfigMigrations.VERSION_KEY in upgraded)
        assertEquals("x", upgraded.getValue("fileNamePattern").jsonPrimitive.content)
    }

    @Test
    fun `a config from a newer app is rejected rather than silently misread`() {
        val future = StrategyConfigMigrations.currentApiVersion + 1
        val config = Json.parseToJsonElement("""{"${StrategyConfigMigrations.VERSION_KEY}": $future}""").jsonObject
        assertFailsWith<IllegalArgumentException> { StrategyConfigMigrations.upgradeApi(config) }
    }
}

class SharedRulesMigrationTest {
    @Test
    fun `v0 csv conditions, content rules, value columns and asset aliases decode onto the shared rules`() {
        val legacy =
            """
            {
              "identificationColumns": ["Date", "Kind", "Payee", "Memo", "Amount", "Fee", "Fee asset", "Currency"],
              "fieldMappings": {
                "TARGET_ACCOUNT": {
                  "type": "com.moneymanager.domain.model.csvstrategy.ConditionalAccountMapping",
                  "fieldType": "TARGET_ACCOUNT",
                  "conditions": [
                    {"columnName": "Kind", "operator": "EQUALS_VALUE", "value": "card", "otherColumnName": null},
                    {"columnName": "Payee", "operator": "EQUALS_COLUMN", "value": null, "otherColumnName": "Memo"},
                    {"columnName": "Payee", "operator": "NOT_EQUALS_COLUMN", "value": null, "otherColumnName": "Kind"},
                    {"columnName": "Memo", "operator": "IS_BLANK", "value": null, "otherColumnName": null},
                    {"columnName": "Kind", "operator": "IS_NOT_BLANK", "value": null, "otherColumnName": null}
                  ],
                  "whenTrue": {"type": "com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping", "fieldType": "TARGET_ACCOUNT", "accountId": 7},
                  "whenFalse": {"type": "com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping", "fieldType": "TARGET_ACCOUNT", "accountId": 8}
                },
                "DESCRIPTION": {
                  "type": "com.moneymanager.domain.model.csvstrategy.DirectColumnMapping",
                  "fieldType": "DESCRIPTION", "columnName": "Payee", "fallbackColumns": ["Memo"],
                  "extraction": {"pattern": "^(.*?),", "outputTemplate": "$1"}
                },
                "CURRENCY": {"type": "com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping", "fieldType": "CURRENCY", "columnName": "Currency"},
                "AMOUNT": {
                  "type": "com.moneymanager.domain.model.csvstrategy.AmountParsingMapping",
                  "fieldType": "AMOUNT", "mode": "SINGLE_COLUMN", "amountColumnName": "Amount",
                  "feeColumnName": "Fee", "feeCurrencyColumnName": "Fee asset"
                }
              },
              "contentMatchRules": [{"columnName": "Kind", "pattern": "^card$"}],
              "assetAliases": {"KNCL": "KNC"}
            }
            """.trimIndent()

        val config = CsvStrategyJsonCodec.decode(legacy)

        val target = config.fieldMappings.getValue(TransferField.TARGET_ACCOUNT) as ConditionalAccountMapping
        assertEquals(
            setOf(
                Condition("Kind", ConditionOp.EQUALS, value = "card"),
                Condition("Payee", ConditionOp.EQUALS_PATH, otherPath = "Memo"),
                Condition("Payee", ConditionOp.NOT_EQUALS_PATH, otherPath = "Kind"),
                Condition("Memo", ConditionOp.BLANK),
                Condition("Kind", ConditionOp.NOT_BLANK),
            ),
            target.conditions.toSet(),
        )
        assertEquals(
            ValueExpr(listOf("Payee", "Memo"), Extraction("^(.*?),", "$1")),
            (config.fieldMappings.getValue(TransferField.DESCRIPTION) as DirectColumnMapping).value,
        )
        assertEquals(ValueExpr(listOf("Currency")), (config.fieldMappings.getValue(TransferField.CURRENCY) as CurrencyLookupMapping).value)
        assertEquals(
            ValueExpr(listOf("Fee asset")),
            (config.fieldMappings.getValue(TransferField.AMOUNT) as AmountParsingMapping).feeCurrency,
        )
        assertEquals(listOf(Condition("Kind", ConditionOp.MATCHES, value = "^card$")), config.contentMatchRules)
        assertEquals(AssetCodeRules(aliases = mapOf("KNCL" to "KNC")), config.assetCodes)
    }

    @Test
    fun `v0 api predicates, exclusions, declines and asset rules decode onto the shared rules`() {
        val legacyMappings =
            """
            {
              "amountField": "amount",
              "declineReasonField": "decline_reason",
              "declineStatusField": "status",
              "declinedStatusValues": ["DECLINED", "REVERSED"],
              "excludeField": "type",
              "excludeValues": ["trade", "margin"],
              "itemFilters": [{"path": "state", "op": "OBJECT_NON_EMPTY"}]
            }
            """.trimIndent()
        val legacy =
            """
            {
              "baseUrl": "https://example.com",
              "authType": "BEARER_TOKEN",
              "accountsEndpoint": {"path": "/accounts", "responseArrayKey": "accounts"},
              "transactionsEndpoint": {"path": "/transactions", "responseArrayKey": "transactions"},
              "accountMappings": {},
              "transactionMappings": $legacyMappings,
              "builtInCounterpartyRules": [
                {"name": "ATM", "predicates": [
                  {"path": "labels", "op": "ARRAY_ANY_STARTS_WITH", "value": "atm"},
                  {"path": "merchant", "op": "OBJECT_EMPTY"},
                  {"path": "mcc", "op": "IN", "value": "6010,6011"}
                ]}
              ],
              "assetAliases": {"XXBT": "BTC"},
              "assetSuffixesToStrip": [".F"]
            }
            """.trimIndent()

        val config = ApiStrategyJsonCodec.decode(legacy)

        val mappings = config.transactionMappings
        assertEquals(
            listOf(Condition("decline_reason", ConditionOp.NOT_BLANK), Condition("status", ConditionOp.IN, value = "DECLINED,REVERSED")),
            mappings.declinedWhen,
        )
        assertEquals(listOf(Condition("type", ConditionOp.IN, value = "margin,trade")), mappings.excludeWhen)
        assertEquals(listOf(Condition("state", ConditionOp.NON_EMPTY_OBJECT)), mappings.itemFilters)
        assertEquals(
            setOf(
                Condition("labels", ConditionOp.ANY_ELEMENT_STARTS_WITH, value = "atm"),
                Condition("merchant", ConditionOp.EMPTY_OBJECT),
                Condition("mcc", ConditionOp.IN, value = "6010,6011"),
            ),
            config.builtInCounterpartyRules
                .single()
                .predicates
                .toSet(),
        )
        assertEquals(AssetCodeRules(aliases = mapOf("XXBT" to "BTC"), stripSuffixes = setOf(".F")), config.assetCodes)
    }
}

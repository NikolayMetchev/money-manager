package com.moneymanager.database.json

import com.moneymanager.domain.model.apistrategy.ApiAccountsSource
import com.moneymanager.domain.model.apistrategy.ApiEndpointKind
import com.moneymanager.domain.model.apistrategy.ApiLedgerTrades
import com.moneymanager.domain.model.apistrategy.ApiPaging
import com.moneymanager.domain.model.apistrategy.WindowBoundFormat
import com.moneymanager.domain.model.csvstrategy.AccountRule
import com.moneymanager.domain.model.csvstrategy.AccountRulesMapping
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.LegAssembly
import com.moneymanager.domain.model.csvstrategy.LegGroupRule
import com.moneymanager.domain.model.csvstrategy.LegSide
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.rules.AssetCodeRules
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.Direction
import com.moneymanager.domain.model.rules.Extraction
import com.moneymanager.domain.model.rules.FeeRule
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
                  "whenTrue": {"type": "com.moneymanager.domain.model.csvstrategy.AccountLookupMapping", "fieldType": "TARGET_ACCOUNT", "columnName": "Payee"},
                  "whenFalse": {"type": "com.moneymanager.domain.model.csvstrategy.AccountLookupMapping", "fieldType": "TARGET_ACCOUNT", "columnName": "Memo"}
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

        val target = config.fieldMappings.getValue(TransferField.TARGET_ACCOUNT) as AccountRulesMapping
        assertEquals(listOf(listOf("Payee"), listOf("Memo")), target.rules.map { it.value.paths })
        assertEquals(emptyList(), target.rules.last().conditions)
        assertEquals(
            setOf(
                Condition("Kind", ConditionOp.EQUALS, value = "card"),
                Condition("Payee", ConditionOp.EQUALS_PATH, otherPath = "Memo"),
                Condition("Payee", ConditionOp.NOT_EQUALS_PATH, otherPath = "Kind"),
                Condition("Memo", ConditionOp.BLANK),
                Condition("Kind", ConditionOp.NOT_BLANK),
            ),
            target.rules
                .first()
                .conditions
                .toSet(),
        )
        assertEquals(
            ValueExpr(listOf("Payee", "Memo"), Extraction("^(.*?),", "$1")),
            (config.fieldMappings.getValue(TransferField.DESCRIPTION) as DirectColumnMapping).value,
        )
        assertEquals(ValueExpr(listOf("Currency")), (config.fieldMappings.getValue(TransferField.CURRENCY) as CurrencyLookupMapping).value)
        assertEquals(
            ValueExpr(listOf("Fee asset")),
            (config.fieldMappings.getValue(TransferField.AMOUNT) as AmountParsingMapping).fee?.currency,
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

        val mappings = checkNotNull(config.bankTransactions?.transactionMappings)
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

class OnePipelineMigrationTest {
    private fun legacy(
        synthetic: Boolean,
        pagination: String,
    ) = """
        {
          "configVersion": 1,
          "baseUrl": "https://example.com",
          "authType": "${if (synthetic) "SIGNED" else "BEARER_TOKEN"}",
          "accountsEndpoint": {"path": "/accounts", "responseArrayKey": "accounts"},
          "transactionsEndpoint": {"path": "/transactions", "responseArrayKey": "items", "pagination": $pagination},
          "accountMappings": {"idField": "uid"},
          "transactionMappings": {"amountField": "amt"},
          ${if (synthetic) "\"syntheticAccount\": {\"name\": \"X\", \"externalId\": \"x\"}," else ""}
          "dataEndpoints": [
            {"endpoint": {"path": "/trades", "responseArrayKey": "", "pagination": $pagination}, "kind": "TRADES",
             "tradeMappings": {"instrumentField": "unused", "splitMode": "EXPLICIT_FIELDS", "baseQuantityField": "q", "timestampField": "t", "idField": "i"}}
          ]
        }
        """.trimIndent()

    @Test
    fun `a bank feed's default cursor mode becomes a before-cursor walk while an exchange endpoint's becomes offset paging`() {
        val pagination =
            """{"mode": "CURSOR", "offsetParam": "ofs", "limitValue": 50, "cursorParam": "before", "cursorResponseField": "created"}"""

        val bank = ApiStrategyJsonCodec.decode(legacy(synthetic = false, pagination = pagination))
        val accounts = assertIs<ApiAccountsSource.Downloaded>(bank.accounts)
        assertEquals("uid", accounts.mappings.idField)
        val feed = checkNotNull(bank.bankTransactions)
        assertEquals("amt", feed.transactionMappings?.amountField)
        assertEquals(ApiPaging.BeforeCursor(), feed.endpoint.pagination?.paging)
        assertEquals(true, feed.endpoint.pagination?.sendLimitParam)
        val trades = bank.dataEndpoints.single { it.kind == ApiEndpointKind.TRADES }
        assertEquals(ApiPaging.Offset(param = "ofs"), trades.endpoint.pagination?.paging)
        assertEquals(null, trades.tradeMappings?.instrumentField)

        val exchange = ApiStrategyJsonCodec.decode(legacy(synthetic = true, pagination = pagination))
        assertEquals(ApiAccountsSource.Single(name = "X", externalId = "x"), exchange.accounts)
        assertTrue(exchange.dataEndpoints.none { it.kind == ApiEndpointKind.BANK_TRANSACTIONS })
    }

    @Test
    fun `a bank date window always sent ISO bounds and never paged within a window`() {
        val pagination = """{"mode": "DATE_WINDOW", "windowBoundFormat": "EPOCH_MS", "offsetParam": "ofs", "sendLimitParam": true}"""

        val bank = ApiStrategyJsonCodec.decode(legacy(synthetic = false, pagination = pagination))
        val feed = checkNotNull(bank.bankTransactions?.endpoint?.pagination)
        assertEquals(WindowBoundFormat.ISO_8601, feed.window?.boundFormat)
        assertEquals(ApiPaging.Single, feed.paging)
        assertEquals(false, feed.sendLimitParam)

        val trades =
            checkNotNull(
                bank.dataEndpoints
                    .single { it.kind == ApiEndpointKind.TRADES }
                    .endpoint.pagination,
            )
        assertEquals(WindowBoundFormat.EPOCH_MS, trades.window?.boundFormat)
        assertEquals(ApiPaging.Offset(param = "ofs"), trades.paging)
        assertEquals(true, trades.sendLimitParam)
    }

    @Test
    fun `token modes keep their incremental position field only when unwindowed`() {
        val token =
            """{"mode": "TOKEN_CURSOR", "nextCursorField": "next", "cursorParam": "c", """ +
                """"cursorResponseField": "created_at", "nextCursorUrlEncoded": true}"""
        val exchange = ApiStrategyJsonCodec.decode(legacy(synthetic = true, pagination = token))
        assertEquals(
            ApiPaging.Token(tokenField = "next", param = "c", urlEncoded = true, positionField = "created_at"),
            exchange.dataEndpoints
                .single()
                .endpoint.pagination
                ?.paging,
        )

        val windowed = """{"mode": "DATE_WINDOW", "nextCursorField": "next", "cursorParam": "c"}"""
        val windowedExchange = ApiStrategyJsonCodec.decode(legacy(synthetic = true, pagination = windowed))
        assertEquals(
            ApiPaging.Token(tokenField = "next", param = "c"),
            windowedExchange.dataEndpoints
                .single()
                .endpoint.pagination
                ?.paging,
        )
    }
}

class AccountRulesMigrationTest {
    private val csvPackage = "com.moneymanager.domain.model.csvstrategy"

    private fun csvV1(
        target: String,
        amount: String = amountJson(""),
    ) = """
        {
          "configVersion": 1,
          "identificationColumns": ["Payee"],
          "fieldMappings": {"TARGET_ACCOUNT": $target, "AMOUNT": $amount}
        }
        """.trimIndent()

    private fun amountJson(
        flags: String,
        columns: String = SINGLE_COLUMN,
    ) = """{"type": "$csvPackage.AmountParsingMapping", "fieldType": "AMOUNT", $columns$flags}"""

    private fun targetRules(json: String): AccountRulesMapping =
        assertIs<AccountRulesMapping>(CsvStrategyJsonCodec.decode(json).fieldMappings[TransferField.TARGET_ACCOUNT])

    @Test
    fun `regex rules become pattern rules ahead of a fallback over the primary and fallback columns`() {
        val mapping =
            targetRules(
                csvV1(
                    """
                    {"type": "$csvPackage.RegexAccountMapping", "fieldType": "TARGET_ACCOUNT", "columnName": "Payee",
                     "fallbackColumns": ["Memo"], "defaultCategoryId": 4,
                     "rules": [
                       {"pattern": "^TESCO", "accountName": "Tesco"},
                       {"pattern": "^PAY (.*)", "accountNameTemplate": "$1", "accountName": "Someone", "counterpartyIsPerson": true, "personNameTemplate": "$1"},
                       {"pattern": "^Deposit$", "accountName": "Funding", "counterpartyIsUnidentified": true}
                     ]}
                    """.trimIndent(),
                ),
            )

        val payee = ValueExpr(listOf("Payee"))
        assertEquals(
            AccountRulesMapping(
                fieldType = TransferField.TARGET_ACCOUNT,
                rules =
                    listOf(
                        AccountRule(value = payee, pattern = "^TESCO", name = "Tesco"),
                        AccountRule(
                            value = payee,
                            pattern = "^PAY (.*)",
                            name = "$1",
                            fallbackName = "Someone",
                            counterpartyIsPerson = true,
                            personName = "$1",
                        ),
                        AccountRule(value = payee, pattern = "^Deposit$", name = "Funding", counterpartyIsUnidentified = true),
                        AccountRule(value = ValueExpr(listOf("Payee", "Memo"))),
                    ),
                defaultCategoryId = 4,
            ),
            mapping,
        )
    }

    @Test
    fun `template and attribute-match mappings trim their value`() {
        val template =
            targetRules(
                csvV1(
                    """{"type": "$csvPackage.TemplateAccountMapping", "fieldType": "TARGET_ACCOUNT", "columnName": "Currency", "prefix": "Wise: ", "suffix": " pot"}""",
                ),
            )
        assertEquals(listOf(AccountRule(value = ValueExpr(listOf("Currency")), trim = true, name = "Wise: {value} pot")), template.rules)

        val attribute =
            targetRules(
                csvV1(
                    """{"type": "$csvPackage.AttributeMatchAccountMapping", "fieldType": "TARGET_ACCOUNT", "columnName": "Card", "attributeTypeName": "card-last4"}""",
                ),
            )
        assertEquals(
            listOf(
                AccountRule(value = ValueExpr(listOf("Card")), trim = true, attributeTypeName = "card-last4"),
                AccountRule(value = ValueExpr(listOf("Card")), trim = true),
            ),
            attribute.rules,
        )
    }

    @Test
    fun `amount flips become a direction`() {
        fun direction(
            flags: String,
            columns: String = SINGLE_COLUMN,
        ): Direction {
            val amount = amountJson(flags, columns)
            val target = """{"type": "$csvPackage.AccountLookupMapping", "fieldType": "TARGET_ACCOUNT", "columnName": "Payee"}"""
            val config = CsvStrategyJsonCodec.decode(csvV1(target, amount))
            return assertIs<AmountParsingMapping>(config.fieldMappings[TransferField.AMOUNT]).direction
        }

        assertEquals(Direction.Outgoing, direction(""))
        assertEquals(Direction.Outgoing, direction(""", "negateValues": true"""))
        assertEquals(Direction.AmountSign(), direction(""", "flipAccountsOnPositive": true"""))
        assertEquals(
            Direction.AmountSign(positiveIsIncoming = false),
            direction(""", "flipAccountsOnPositive": true, "negateValues": true"""),
        )
        // The legacy parser never negated credit/debit columns, so the flag must not reverse their direction.
        assertEquals(
            Direction.AmountSign(),
            direction(""", "flipAccountsOnPositive": true, "negateValues": true""", CREDIT_DEBIT_COLUMNS),
        )
    }

    @Test
    fun `api sign settings become a direction and fixed endpoint directions are dropped`() {
        fun mappings(fields: String) =
            """
            {
              "configVersion": 2,
              "baseUrl": "https://example.com",
              "accounts": {"type": "single", "name": "X", "externalId": "x"},
              "dataEndpoints": [
                {"endpoint": {"path": "/deposits", "responseArrayKey": ""}, "kind": "DEPOSITS", "fixedDirection": "IN",
                 "transactionMappings": {"amountField": "amount"$fields}}
              ]
            }
            """.trimIndent()

        fun direction(fields: String): Direction? =
            ApiStrategyJsonCodec
                .decode(mappings(fields))
                .dataEndpoints
                .single()
                .transactionMappings
                ?.direction

        assertEquals(null, direction(""))
        assertEquals(Direction.AmountSign(), direction(""", "directionFromAmountSign": true"""))
        assertEquals(
            Direction.Field(path = "side", incomingValues = setOf("CREDIT", "IN")),
            direction(""", "signSource": "FIELD", "signField": "side", "creditValues": ["IN", "CREDIT"]"""),
        )
        assertEquals(null, direction(""", "signSource": "AMOUNT", "signField": "side""""))
    }
}

class LegGroupsAndFeesMigrationTest {
    private val csvPackage = "com.moneymanager.domain.model.csvstrategy"

    @Test
    fun `conversion and trade-group configs become leg rules, conversion first`() {
        val legacy =
            """
            {
              "configVersion": 2,
              "identificationColumns": ["Kind"],
              "fieldMappings": {},
              "conversionConfig": {
                "signalColumn": "Kind", "debitPattern": "^swap_out$", "creditPattern": "^swap_in$",
                "conversionAccountName": "Conversions",
                "conversionAccountRules": [{"column": "Asset", "pattern": "^DUST$", "accountName": "Dust"}],
                "pairingKeyPattern": "^(swap)_", "pairingKeyColumns": ["Ref"],
                "pairingWindowSeconds": 5, "relationshipTypeName": "conversion"
              },
              "tradeGroupConfig": {
                "signalColumn": "Kind", "debitPattern": "^fill$", "creditPattern": "^fill$", "sideAmountColumn": "Amount",
                "groupingWindowSeconds": 1, "descriptionTemplate": "Swap {from}/{to}", "reconcileWindowSeconds": 3
              }
            }
            """.trimIndent()

        val config = CsvStrategyJsonCodec.decode(legacy)

        val (conversion, trade) = config.legGroups
        assertEquals(
            LegGroupRule(
                legWhen =
                    listOf(
                        Condition("Kind", ConditionOp.MATCHES, "(?:^swap_out$)|(?:^swap_in$)"),
                        Condition("Kind", ConditionOp.NOT_BLANK),
                    ),
                side = LegSide.DebitWhen(listOf(Condition("Kind", ConditionOp.MATCHES, "^swap_out$"))),
                key = listOf(ValueExpr(listOf("Kind"), Extraction("^(swap)_", "$1")), ValueExpr(listOf("Ref"))),
                windowSeconds = 5,
                assembly =
                    LegAssembly.ThroughAccount(
                        accounts =
                            listOf(
                                AccountRule(
                                    conditions = listOf(Condition("Asset", ConditionOp.MATCHES, "^DUST$")),
                                    value = ValueExpr(listOf("Kind")),
                                    name = "Dust",
                                ),
                                AccountRule(value = ValueExpr(listOf("Kind")), name = "Conversions"),
                            ),
                        relationshipTypeName = "conversion",
                    ),
            ),
            conversion,
        )
        assertEquals(
            LegGroupRule(
                legWhen = listOf(Condition("Kind", ConditionOp.MATCHES, "^fill$")),
                side = LegSide.Sign("Amount"),
                windowSeconds = 1,
                assembly = LegAssembly.Trade("Swap {from}/{to}"),
                reconcileWindowSeconds = 3,
            ),
            trade,
        )
    }

    @Test
    fun `a csv fee column becomes a fee rule`() {
        val legacy =
            """
            {
              "configVersion": 2,
              "identificationColumns": ["Amount"],
              "fieldMappings": {
                "AMOUNT": {"type": "$csvPackage.AmountParsingMapping", "fieldType": "AMOUNT", "mode": "SINGLE_COLUMN",
                  "amountColumnName": "Amount", "feeColumnName": "Fee",
                  "feeConditions": [{"path": "Dir", "op": "EQUALS", "value": "OUT"}],
                  "feeCurrency": {"paths": ["Fee asset"]}}
              }
            }
            """.trimIndent()

        val amount = assertIs<AmountParsingMapping>(CsvStrategyJsonCodec.decode(legacy).fieldMappings[TransferField.AMOUNT])

        assertEquals(
            FeeRule(
                amount = ValueExpr(listOf("Fee")),
                currency = ValueExpr(listOf("Fee asset")),
                conditions = listOf(Condition("Dir", ConditionOp.EQUALS, value = "OUT")),
            ),
            amount.fee,
        )
    }

    @Test
    fun `api fee fields and ledger grouping fields become a fee rule and ledger trades`() {
        val legacy =
            """
            {
              "configVersion": 3,
              "baseUrl": "https://example.com",
              "accounts": {"type": "single", "name": "X", "externalId": "x"},
              "dataEndpoints": [
                {"endpoint": {"path": "/ledger", "responseArrayKey": ""}, "kind": "DEPOSITS",
                 "transactionMappings": {"amountField": "amount",
                   "feeAmountField": "fill.commission", "feeDescriptionField": "note", "feeIncludedInAmount": true,
                   "feeInstrumentField": "fill.product_id", "feeInstrumentSeparator": ".",
                   "reconcileTradeAmountsField": "trade.id", "reconcileTradeAmountsFallbackFields": ["buy.id"],
                   "unpairedTradeLegCounterAmountField": "native_amount", "unpairedTradeLegFundingAccountName": "Cards"}},
                {"endpoint": {"path": "/trades", "responseArrayKey": ""}, "kind": "TRADES",
                 "tradeMappings": {"instrumentField": "pair", "baseQuantityField": "qty", "timestampField": "t", "idField": "i",
                   "feeField": "fee", "feeCurrencyField": "feeAsset"}}
              ]
            }
            """.trimIndent()

        val config = ApiStrategyJsonCodec.decode(legacy)

        val (ledger, trades) = config.dataEndpoints
        val mappings = checkNotNull(ledger.transactionMappings)
        assertEquals(
            FeeRule(
                amount = ValueExpr(listOf("fill.commission")),
                description = ValueExpr(listOf("note")),
                includedInAmount = true,
                chargedOnAsset = ValueExpr(listOf("fill.product_id"), Extraction("^.*\\.(.*)$", "$1")),
            ),
            mappings.fee,
        )
        assertEquals(
            ApiLedgerTrades(
                key = ValueExpr(listOf("trade.id", "buy.id")),
                unpairedCounterAmountPath = "native_amount",
                unpairedFundingAccountName = "Cards",
            ),
            mappings.ledgerTrades,
        )
        assertEquals(
            FeeRule(amount = ValueExpr(listOf("fee")), currency = ValueExpr(listOf("feeAsset"))),
            checkNotNull(trades.tradeMappings).fee,
        )
    }
}

private const val SINGLE_COLUMN = """"mode": "SINGLE_COLUMN", "amountColumnName": "Amount""""
private const val CREDIT_DEBIT_COLUMNS = """"mode": "CREDIT_DEBIT_COLUMNS", "creditColumnName": "In", "debitColumnName": "Out""""

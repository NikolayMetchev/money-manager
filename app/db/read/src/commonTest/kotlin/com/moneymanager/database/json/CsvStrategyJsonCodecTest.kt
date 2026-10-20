package com.moneymanager.database.json

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.csvstrategy.AccountFromRulesMapping
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.TimezoneLookupMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.rules.AccountNaming
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.FeeRule
import com.moneymanager.domain.model.rules.ForeignAmount
import com.moneymanager.domain.model.rules.LegRole
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleValue
import com.moneymanager.domain.model.rules.StrategyRule
import com.moneymanager.domain.model.rules.ValueExpr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CsvStrategyJsonCodecTest {
    @Test
    fun `encode and decode HardCodedAccountMapping`() {
        val mapping =
            HardCodedAccountMapping(
                fieldType = TransferField.SOURCE_ACCOUNT,
                accountId = AccountId(123),
            )
        val mappings = mapOf(TransferField.SOURCE_ACCOUNT to mapping)

        val json = CsvStrategyJsonCodec.encode(CsvStrategyConfig(emptySet(), mappings))
        val decoded = CsvStrategyJsonCodec.decode(json).fieldMappings

        assertEquals(1, decoded.size)
        val decodedMapping = decoded[TransferField.SOURCE_ACCOUNT]
        assertIs<HardCodedAccountMapping>(decodedMapping)
        assertEquals(AccountId(123), decodedMapping.accountId)
        assertEquals(TransferField.SOURCE_ACCOUNT, decodedMapping.fieldType)
    }

    @Test
    fun `encode and decode AccountFromRulesMapping`() {
        val mapping = AccountFromRulesMapping(TransferField.TARGET_ACCOUNT, defaultCategoryId = 7)
        val mappings = mapOf(TransferField.TARGET_ACCOUNT to mapping)

        val json = CsvStrategyJsonCodec.encode(CsvStrategyConfig(emptySet(), mappings))
        val decoded = CsvStrategyJsonCodec.decode(json).fieldMappings

        assertEquals(mapping, decoded[TransferField.TARGET_ACCOUNT])
    }

    @Test
    fun `encode and decode account rules in their order`() {
        val rules =
            listOf(
                StrategyRule.accounts(
                    AccountSide.SOURCE,
                    listOf(AccountNaming(value = ValueExpr(listOf("Source currency")), trim = true, name = "Wise: {value}")),
                ),
                StrategyRule.accounts(
                    AccountSide.TARGET,
                    listOf(AccountNaming(value = ValueExpr(listOf("Target currency")), trim = true, name = "Wise: {value}")),
                    conditions =
                        listOf(
                            Condition("Source name", ConditionOp.EQUALS_PATH, otherPath = "Target name"),
                            Condition("Source name", ConditionOp.NOT_BLANK),
                        ),
                ),
                StrategyRule.accounts(
                    AccountSide.TARGET,
                    listOf(
                        AccountNaming(value = ValueExpr(listOf("Name")), pattern = ".*paxos.*", name = "Paxos"),
                        AccountNaming(value = ValueExpr(listOf("Name")), pattern = ".*crypto\\.com.*", name = "Crypto.com"),
                        AccountNaming(value = ValueExpr(listOf("Name", "Type"))),
                    ),
                ),
            )

        val json = CsvStrategyJsonCodec.encode(CsvStrategyConfig(emptySet(), emptyMap(), rules = rules))

        assertEquals(rules, CsvStrategyJsonCodec.decode(json).rules)
    }

    @Test
    fun `encode and decode every rule action`() {
        val rules =
            listOf(
                StrategyRule(
                    name = "rewrites",
                    conditions = listOf(Condition("Direction", ConditionOp.EQUALS, value = "IN")),
                    actions =
                        listOf(
                            RuleAction.SwapValues("Source name", "Target name"),
                            RuleAction.SetValue("Kind", RuleValue.constant("swap")),
                            RuleAction.Flip,
                        ),
                ),
                StrategyRule(
                    conditions = listOf(Condition("Amount", ConditionOp.POSITIVE)),
                    actions = listOf(RuleAction.SetDirection(incoming = true)),
                ),
                StrategyRule(
                    actions =
                        listOf(
                            RuleAction.Exclude(RuleValue.read("Status")),
                            RuleAction.ExternalId(RuleValue.Template("{ID}-{Kind}")),
                            RuleAction.CounterpartyIsPerson,
                            RuleAction.IgnoreCounterpartyId,
                            RuleAction.Attribute("wise-id", RuleValue.read("ID"), uniqueIdentifier = true),
                            RuleAction.Fee(
                                FeeRule(
                                    amount = ValueExpr(listOf("Source fee amount")),
                                    conditions = listOf(Condition("Direction", ConditionOp.EQUALS, value = "OUT")),
                                ),
                            ),
                            RuleAction.Foreign(
                                ForeignAmount(amount = ValueExpr(listOf("Local amount")), currency = ValueExpr(listOf("Local currency"))),
                            ),
                            RuleAction.ThroughConduit("Curve"),
                            RuleAction.TradeSide(buy = true),
                            RuleAction.Leg("trade", LegRole.BySign("Change")),
                            RuleAction.Leg("conversion", LegRole.Debit),
                            RuleAction.LedgerTradeLeg,
                            RuleAction.Skip,
                        ),
                ),
            )

        val json = CsvStrategyJsonCodec.encode(CsvStrategyConfig(emptySet(), emptyMap(), rules = rules))

        assertEquals(rules, CsvStrategyJsonCodec.decode(json).rules)
    }

    @Test
    fun `encode and decode DateTimeParsingMapping`() {
        val mapping =
            DateTimeParsingMapping(
                fieldType = TransferField.TIMESTAMP,
                dateColumnName = "Date",
                dateFormat = "dd/MM/yyyy",
                timeColumnName = "Time",
                timeFormat = "HH:mm:ss",
            )
        val mappings = mapOf(TransferField.TIMESTAMP to mapping)

        val json = CsvStrategyJsonCodec.encode(CsvStrategyConfig(emptySet(), mappings))
        val decoded = CsvStrategyJsonCodec.decode(json).fieldMappings

        val decodedMapping = decoded[TransferField.TIMESTAMP]
        assertIs<DateTimeParsingMapping>(decodedMapping)
        assertEquals("Date", decodedMapping.dateColumnName)
        assertEquals("dd/MM/yyyy", decodedMapping.dateFormat)
        assertEquals("Time", decodedMapping.timeColumnName)
        assertEquals("HH:mm:ss", decodedMapping.timeFormat)
        assertEquals("12:00:00", decodedMapping.defaultTime)
    }

    @Test
    fun `encode and decode DirectColumnMapping`() {
        val mapping =
            DirectColumnMapping(fieldType = TransferField.DESCRIPTION, value = ValueExpr(listOf("Description")))
        val mappings = mapOf(TransferField.DESCRIPTION to mapping)

        val json = CsvStrategyJsonCodec.encode(CsvStrategyConfig(emptySet(), mappings))
        val decoded = CsvStrategyJsonCodec.decode(json).fieldMappings

        val decodedMapping = decoded[TransferField.DESCRIPTION]
        assertIs<DirectColumnMapping>(decodedMapping)
        assertEquals("Description", decodedMapping.value.primaryPath)
    }

    @Test
    fun `encode and decode AmountParsingMapping with SINGLE_COLUMN mode`() {
        val mapping =
            AmountParsingMapping(
                fieldType = TransferField.AMOUNT,
                mode = AmountMode.SINGLE_COLUMN,
                amountColumnName = "Amount",
            )
        val mappings = mapOf(TransferField.AMOUNT to mapping)

        val json = CsvStrategyJsonCodec.encode(CsvStrategyConfig(emptySet(), mappings))
        val decoded = CsvStrategyJsonCodec.decode(json).fieldMappings

        val decodedMapping = decoded[TransferField.AMOUNT]
        assertIs<AmountParsingMapping>(decodedMapping)
        assertEquals(AmountMode.SINGLE_COLUMN, decodedMapping.mode)
        assertEquals("Amount", decodedMapping.amountColumnName)
    }

    @Test
    fun `encode and decode AmountParsingMapping with CREDIT_DEBIT_COLUMNS mode`() {
        val mapping =
            AmountParsingMapping(
                fieldType = TransferField.AMOUNT,
                mode = AmountMode.CREDIT_DEBIT_COLUMNS,
                creditColumnName = "Credit",
                debitColumnName = "Debit",
            )
        val mappings = mapOf(TransferField.AMOUNT to mapping)

        val json = CsvStrategyJsonCodec.encode(CsvStrategyConfig(emptySet(), mappings))
        val decoded = CsvStrategyJsonCodec.decode(json).fieldMappings

        val decodedMapping = decoded[TransferField.AMOUNT]
        assertIs<AmountParsingMapping>(decodedMapping)
        assertEquals(AmountMode.CREDIT_DEBIT_COLUMNS, decodedMapping.mode)
        assertEquals("Credit", decodedMapping.creditColumnName)
        assertEquals("Debit", decodedMapping.debitColumnName)
    }

    @Test
    fun `encode and decode HardCodedCurrencyMapping`() {
        val currencyId = CurrencyId(1L)
        val mapping =
            HardCodedCurrencyMapping(
                fieldType = TransferField.CURRENCY,
                currencyId = currencyId,
            )
        val mappings = mapOf(TransferField.CURRENCY to mapping)

        val json = CsvStrategyJsonCodec.encode(CsvStrategyConfig(emptySet(), mappings))
        val decoded = CsvStrategyJsonCodec.decode(json).fieldMappings

        val decodedMapping = decoded[TransferField.CURRENCY]
        assertIs<HardCodedCurrencyMapping>(decodedMapping)
        assertEquals(currencyId, decodedMapping.currencyId)
    }

    @Test
    fun `encode and decode CurrencyLookupMapping`() {
        val mapping =
            CurrencyLookupMapping(fieldType = TransferField.CURRENCY, value = ValueExpr(listOf("Currency")))
        val mappings = mapOf(TransferField.CURRENCY to mapping)

        val json = CsvStrategyJsonCodec.encode(CsvStrategyConfig(emptySet(), mappings))
        val decoded = CsvStrategyJsonCodec.decode(json).fieldMappings

        val decodedMapping = decoded[TransferField.CURRENCY]
        assertIs<CurrencyLookupMapping>(decodedMapping)
        assertEquals("Currency", decodedMapping.value.primaryPath)
    }

    @Test
    fun `encode and decode complete strategy mappings`() {
        val currencyId = CurrencyId(1L)
        val mappings =
            mapOf(
                TransferField.SOURCE_ACCOUNT to
                    HardCodedAccountMapping(
                        fieldType = TransferField.SOURCE_ACCOUNT,
                        accountId = AccountId(1),
                    ),
                TransferField.TARGET_ACCOUNT to AccountFromRulesMapping(TransferField.TARGET_ACCOUNT),
                TransferField.TIMESTAMP to
                    DateTimeParsingMapping(
                        fieldType = TransferField.TIMESTAMP,
                        dateColumnName = "Date",
                        dateFormat = "yyyy-MM-dd",
                    ),
                TransferField.DESCRIPTION to
                    DirectColumnMapping(fieldType = TransferField.DESCRIPTION, value = ValueExpr(listOf("Memo"))),
                TransferField.AMOUNT to
                    AmountParsingMapping(fieldType = TransferField.AMOUNT, mode = AmountMode.SINGLE_COLUMN, amountColumnName = "Amount"),
                TransferField.CURRENCY to
                    HardCodedCurrencyMapping(
                        fieldType = TransferField.CURRENCY,
                        currencyId = currencyId,
                    ),
            )

        val json = CsvStrategyJsonCodec.encode(CsvStrategyConfig(emptySet(), mappings))
        val decoded = CsvStrategyJsonCodec.decode(json).fieldMappings

        assertEquals(6, decoded.size)
        assertIs<HardCodedAccountMapping>(decoded[TransferField.SOURCE_ACCOUNT])
        assertIs<AccountFromRulesMapping>(decoded[TransferField.TARGET_ACCOUNT])
        assertIs<DateTimeParsingMapping>(decoded[TransferField.TIMESTAMP])
        assertIs<DirectColumnMapping>(decoded[TransferField.DESCRIPTION])
        assertIs<AmountParsingMapping>(decoded[TransferField.AMOUNT])
        assertIs<HardCodedCurrencyMapping>(decoded[TransferField.CURRENCY])
    }

    @Test
    fun `encode and decode identification columns`() {
        val columns = setOf("Date", "Description", "Amount", "Payee")

        val json = CsvStrategyJsonCodec.encode(CsvStrategyConfig(columns, emptyMap()))
        val decoded = CsvStrategyJsonCodec.decode(json).identificationColumns

        assertEquals(columns, decoded)
    }

    @Test
    fun `encode and decode empty identification columns`() {
        val columns = emptySet<String>()

        val json = CsvStrategyJsonCodec.encode(CsvStrategyConfig(columns, emptyMap()))
        val decoded = CsvStrategyJsonCodec.decode(json).identificationColumns

        assertEquals(columns, decoded)
    }

    @Test
    fun `encoded JSON is valid and parseable`() {
        val mapping =
            HardCodedAccountMapping(
                fieldType = TransferField.SOURCE_ACCOUNT,
                accountId = AccountId(42),
            )
        val mappings = mapOf(TransferField.SOURCE_ACCOUNT to mapping)

        val json = CsvStrategyJsonCodec.encode(CsvStrategyConfig(emptySet(), mappings))

        assertTrue(json.contains("SOURCE_ACCOUNT"))
        assertTrue(json.contains("HardCodedAccountMapping"))
        assertTrue(json.contains("42"))
    }

    @Test
    fun `encode and decode HardCodedTimezoneMapping`() {
        val mapping =
            HardCodedTimezoneMapping(
                fieldType = TransferField.TIMEZONE,
                timezoneId = "Europe/London",
            )
        val mappings = mapOf(TransferField.TIMEZONE to mapping)

        val json = CsvStrategyJsonCodec.encode(CsvStrategyConfig(emptySet(), mappings))
        val decoded = CsvStrategyJsonCodec.decode(json).fieldMappings

        val decodedMapping = decoded[TransferField.TIMEZONE]
        assertIs<HardCodedTimezoneMapping>(decodedMapping)
        assertEquals("Europe/London", decodedMapping.timezoneId)
        assertEquals(TransferField.TIMEZONE, decodedMapping.fieldType)
    }

    @Test
    fun `encode and decode TimezoneLookupMapping`() {
        val mapping =
            TimezoneLookupMapping(
                fieldType = TransferField.TIMEZONE,
                columnName = "Timezone",
            )
        val mappings = mapOf(TransferField.TIMEZONE to mapping)

        val json = CsvStrategyJsonCodec.encode(CsvStrategyConfig(emptySet(), mappings))
        val decoded = CsvStrategyJsonCodec.decode(json).fieldMappings

        val decodedMapping = decoded[TransferField.TIMEZONE]
        assertIs<TimezoneLookupMapping>(decodedMapping)
        assertEquals("Timezone", decodedMapping.columnName)
        assertEquals(TransferField.TIMEZONE, decodedMapping.fieldType)
    }

    @Test
    fun `encode and decode DateTimeParsingMapping with combined date-time format`() {
        val mapping =
            DateTimeParsingMapping(
                fieldType = TransferField.TIMESTAMP,
                dateColumnName = "Created on",
                dateFormat = "yyyy-MM-dd",
                dateTimeFormat = "yyyy-MM-dd HH:mm:ss",
            )
        val mappings = mapOf(TransferField.TIMESTAMP to mapping)

        val json = CsvStrategyJsonCodec.encode(CsvStrategyConfig(emptySet(), mappings))
        val decoded = CsvStrategyJsonCodec.decode(json).fieldMappings

        val decodedMapping = decoded[TransferField.TIMESTAMP]
        assertIs<DateTimeParsingMapping>(decodedMapping)
        assertEquals("yyyy-MM-dd HH:mm:ss", decodedMapping.dateTimeFormat)
    }

    @Test
    fun `decoding a config without optional sections defaults them`() {
        val expected = CsvStrategyConfig<FieldMapping>(identificationColumns = setOf("Date"), fieldMappings = emptyMap())

        val decoded = CsvStrategyJsonCodec.decode("""{"identificationColumns":["Date"],"fieldMappings":{}}""")

        assertEquals(expected, decoded)
    }
}

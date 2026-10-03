package com.moneymanager.database.json

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.csvstrategy.AccountRule
import com.moneymanager.domain.model.csvstrategy.AccountRulesMapping
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.ColumnPairSwap
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.RowPreprocessingRule
import com.moneymanager.domain.model.csvstrategy.TimezoneLookupMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.Direction
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
    fun `encode and decode AccountLookupMapping`() {
        val mapping =
            AccountRulesMapping(fieldType = TransferField.TARGET_ACCOUNT, rules = listOf(AccountRule(value = ValueExpr(listOf("Payee")))))
        val mappings = mapOf(TransferField.TARGET_ACCOUNT to mapping)

        val json = CsvStrategyJsonCodec.encode(CsvStrategyConfig(emptySet(), mappings))
        val decoded = CsvStrategyJsonCodec.decode(json).fieldMappings

        val decodedMapping = decoded[TransferField.TARGET_ACCOUNT]
        assertEquals(mapping, decodedMapping)
    }

    @Test
    fun `encode and decode RegexAccountMapping`() {
        val mapping =
            AccountRulesMapping(
                fieldType = TransferField.TARGET_ACCOUNT,
                rules =
                    listOf(
                        AccountRule(value = ValueExpr(listOf("Name")), pattern = ".*paxos.*", name = "Paxos"),
                        AccountRule(value = ValueExpr(listOf("Name")), pattern = ".*crypto\\.com.*", name = "Crypto.com"),
                        AccountRule(value = ValueExpr(listOf("Name", "Type"))),
                    ),
            )
        val mappings = mapOf(TransferField.TARGET_ACCOUNT to mapping)

        val json = CsvStrategyJsonCodec.encode(CsvStrategyConfig(emptySet(), mappings))
        val decoded = CsvStrategyJsonCodec.decode(json).fieldMappings

        val decodedMapping = decoded[TransferField.TARGET_ACCOUNT]
        assertEquals(mapping, decodedMapping)
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
                direction = Direction.AmountSign(positiveIsIncoming = false),
            )
        val mappings = mapOf(TransferField.AMOUNT to mapping)

        val json = CsvStrategyJsonCodec.encode(CsvStrategyConfig(emptySet(), mappings))
        val decoded = CsvStrategyJsonCodec.decode(json).fieldMappings

        val decodedMapping = decoded[TransferField.AMOUNT]
        assertIs<AmountParsingMapping>(decodedMapping)
        assertEquals(AmountMode.SINGLE_COLUMN, decodedMapping.mode)
        assertEquals("Amount", decodedMapping.amountColumnName)
        assertEquals(Direction.AmountSign(positiveIsIncoming = false), decodedMapping.direction)
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
                TransferField.TARGET_ACCOUNT to
                    AccountRulesMapping(
                        fieldType = TransferField.TARGET_ACCOUNT,
                        rules = listOf(AccountRule(value = ValueExpr(listOf("Payee")))),
                    ),
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
        assertIs<AccountRulesMapping>(decoded[TransferField.TARGET_ACCOUNT])
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
    fun `encode and decode TemplateAccountMapping`() {
        val mapping =
            AccountRulesMapping(
                fieldType = TransferField.SOURCE_ACCOUNT,
                rules = listOf(AccountRule(value = ValueExpr(listOf("Source currency")), trim = true, name = "Wise: {value}")),
            )
        val mappings = mapOf(TransferField.SOURCE_ACCOUNT to mapping)

        val json = CsvStrategyJsonCodec.encode(CsvStrategyConfig(emptySet(), mappings))
        val decoded = CsvStrategyJsonCodec.decode(json).fieldMappings

        val decodedMapping = decoded[TransferField.SOURCE_ACCOUNT]
        assertEquals(mapping, decodedMapping)
    }

    @Test
    fun `encode and decode ConditionalAccountMapping with nested mappings`() {
        val mapping =
            AccountRulesMapping.conditional(
                fieldType = TransferField.TARGET_ACCOUNT,
                conditions =
                    listOf(
                        Condition("Source name", ConditionOp.EQUALS_PATH, otherPath = "Target name"),
                        Condition("Source name", ConditionOp.NOT_BLANK),
                    ),
                whenTrue =
                    AccountRulesMapping(
                        fieldType = TransferField.TARGET_ACCOUNT,
                        rules = listOf(AccountRule(value = ValueExpr(listOf("Target currency")), trim = true, name = "Wise: {value}")),
                    ),
                whenFalse =
                    AccountRulesMapping(
                        fieldType = TransferField.TARGET_ACCOUNT,
                        rules = listOf(AccountRule(value = ValueExpr(listOf("Target name", "Source name")))),
                    ),
            )
        val mappings = mapOf(TransferField.TARGET_ACCOUNT to mapping)

        val json = CsvStrategyJsonCodec.encode(CsvStrategyConfig(emptySet(), mappings))
        val decoded = CsvStrategyJsonCodec.decode(json).fieldMappings

        val decodedMapping = decoded[TransferField.TARGET_ACCOUNT]
        assertEquals(mapping, decodedMapping)
        assertEquals(
            2,
            assertIs<AccountRulesMapping>(decodedMapping)
                .rules
                .first()
                .conditions.size,
        )
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
    fun `encode and decode AmountParsingMapping with conditional fee column`() {
        val mapping =
            AmountParsingMapping(
                fieldType = TransferField.AMOUNT,
                mode = AmountMode.SINGLE_COLUMN,
                amountColumnName = "Source amount (after fees)",
                feeColumnName = "Source fee amount",
                feeConditions = listOf(Condition("Direction", ConditionOp.EQUALS, value = "OUT")),
            )
        val mappings = mapOf(TransferField.AMOUNT to mapping)

        val json = CsvStrategyJsonCodec.encode(CsvStrategyConfig(emptySet(), mappings))
        val decoded = CsvStrategyJsonCodec.decode(json).fieldMappings

        val decodedMapping = decoded[TransferField.AMOUNT]
        assertIs<AmountParsingMapping>(decodedMapping)
        assertEquals("Source fee amount", decodedMapping.feeColumnName)
        assertEquals(ConditionOp.EQUALS, decodedMapping.feeConditions.single().op)
    }

    @Test
    fun `encode and decode row preprocessing rules`() {
        val rules =
            listOf(
                RowPreprocessingRule(
                    conditions = listOf(Condition("Direction", ConditionOp.EQUALS, value = "IN")),
                    columnSwaps = listOf(ColumnPairSwap("Source name", "Target name")),
                    flipSourceAndTarget = true,
                ),
            )

        val json = CsvStrategyJsonCodec.encode(CsvStrategyConfig(emptySet(), emptyMap(), rowPreprocessingRules = rules))
        val decoded = CsvStrategyJsonCodec.decode(json).rowPreprocessingRules

        assertEquals(rules, decoded)
    }

    @Test
    fun `decoding a config without optional sections defaults them`() {
        val expected = CsvStrategyConfig<FieldMapping>(identificationColumns = setOf("Date"), fieldMappings = emptyMap())

        val decoded = CsvStrategyJsonCodec.decode("""{"identificationColumns":["Date"],"fieldMappings":{}}""")

        assertEquals(expected, decoded)
    }
}

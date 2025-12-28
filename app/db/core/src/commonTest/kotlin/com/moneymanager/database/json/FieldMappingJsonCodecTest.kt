@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.json

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.csvstrategy.AccountLookupMapping
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMappingId
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class FieldMappingJsonCodecTest {
    @Test
    fun `encode and decode HardCodedAccountMapping`() {
        val mapping =
            HardCodedAccountMapping(
                id = FieldMappingId(Uuid.random()),
                fieldType = TransferField.SOURCE_ACCOUNT,
                accountId = AccountId(123),
            )
        val mappings = mapOf(TransferField.SOURCE_ACCOUNT to mapping)

        val json = FieldMappingJsonCodec.encode(mappings)
        val decoded = FieldMappingJsonCodec.decode(json)

        assertEquals(1, decoded.size)
        val decodedMapping = decoded[TransferField.SOURCE_ACCOUNT]
        assertIs<HardCodedAccountMapping>(decodedMapping)
        assertEquals(AccountId(123), decodedMapping.accountId)
        assertEquals(TransferField.SOURCE_ACCOUNT, decodedMapping.fieldType)
    }

    @Test
    fun `encode and decode AccountLookupMapping`() {
        val mapping =
            AccountLookupMapping(
                id = FieldMappingId(Uuid.random()),
                fieldType = TransferField.TARGET_ACCOUNT,
                columnName = "Payee",
                createIfMissing = true,
                defaultCategoryId = Category.UNCATEGORIZED_ID,
            )
        val mappings = mapOf(TransferField.TARGET_ACCOUNT to mapping)

        val json = FieldMappingJsonCodec.encode(mappings)
        val decoded = FieldMappingJsonCodec.decode(json)

        val decodedMapping = decoded[TransferField.TARGET_ACCOUNT]
        assertIs<AccountLookupMapping>(decodedMapping)
        assertEquals("Payee", decodedMapping.columnName)
        assertEquals(true, decodedMapping.createIfMissing)
    }

    @Test
    fun `encode and decode DateTimeParsingMapping`() {
        val mapping =
            DateTimeParsingMapping(
                id = FieldMappingId(Uuid.random()),
                fieldType = TransferField.TIMESTAMP,
                dateColumnName = "Date",
                dateFormat = "dd/MM/yyyy",
                timeColumnName = "Time",
                timeFormat = "HH:mm:ss",
                defaultTime = "12:00:00",
            )
        val mappings = mapOf(TransferField.TIMESTAMP to mapping)

        val json = FieldMappingJsonCodec.encode(mappings)
        val decoded = FieldMappingJsonCodec.decode(json)

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
            DirectColumnMapping(
                id = FieldMappingId(Uuid.random()),
                fieldType = TransferField.DESCRIPTION,
                columnName = "Description",
            )
        val mappings = mapOf(TransferField.DESCRIPTION to mapping)

        val json = FieldMappingJsonCodec.encode(mappings)
        val decoded = FieldMappingJsonCodec.decode(json)

        val decodedMapping = decoded[TransferField.DESCRIPTION]
        assertIs<DirectColumnMapping>(decodedMapping)
        assertEquals("Description", decodedMapping.columnName)
    }

    @Test
    fun `encode and decode AmountParsingMapping with SINGLE_COLUMN mode`() {
        val mapping =
            AmountParsingMapping(
                id = FieldMappingId(Uuid.random()),
                fieldType = TransferField.AMOUNT,
                mode = AmountMode.SINGLE_COLUMN,
                amountColumnName = "Amount",
                negateValues = true,
                flipAccountsOnPositive = true,
            )
        val mappings = mapOf(TransferField.AMOUNT to mapping)

        val json = FieldMappingJsonCodec.encode(mappings)
        val decoded = FieldMappingJsonCodec.decode(json)

        val decodedMapping = decoded[TransferField.AMOUNT]
        assertIs<AmountParsingMapping>(decodedMapping)
        assertEquals(AmountMode.SINGLE_COLUMN, decodedMapping.mode)
        assertEquals("Amount", decodedMapping.amountColumnName)
        assertEquals(true, decodedMapping.negateValues)
        assertEquals(true, decodedMapping.flipAccountsOnPositive)
    }

    @Test
    fun `encode and decode AmountParsingMapping with CREDIT_DEBIT_COLUMNS mode`() {
        val mapping =
            AmountParsingMapping(
                id = FieldMappingId(Uuid.random()),
                fieldType = TransferField.AMOUNT,
                mode = AmountMode.CREDIT_DEBIT_COLUMNS,
                creditColumnName = "Credit",
                debitColumnName = "Debit",
            )
        val mappings = mapOf(TransferField.AMOUNT to mapping)

        val json = FieldMappingJsonCodec.encode(mappings)
        val decoded = FieldMappingJsonCodec.decode(json)

        val decodedMapping = decoded[TransferField.AMOUNT]
        assertIs<AmountParsingMapping>(decodedMapping)
        assertEquals(AmountMode.CREDIT_DEBIT_COLUMNS, decodedMapping.mode)
        assertEquals("Credit", decodedMapping.creditColumnName)
        assertEquals("Debit", decodedMapping.debitColumnName)
    }

    @Test
    fun `encode and decode HardCodedCurrencyMapping`() {
        val currencyId = CurrencyId(Uuid.random())
        val mapping =
            HardCodedCurrencyMapping(
                id = FieldMappingId(Uuid.random()),
                fieldType = TransferField.CURRENCY,
                currencyId = currencyId,
            )
        val mappings = mapOf(TransferField.CURRENCY to mapping)

        val json = FieldMappingJsonCodec.encode(mappings)
        val decoded = FieldMappingJsonCodec.decode(json)

        val decodedMapping = decoded[TransferField.CURRENCY]
        assertIs<HardCodedCurrencyMapping>(decodedMapping)
        assertEquals(currencyId, decodedMapping.currencyId)
    }

    @Test
    fun `encode and decode CurrencyLookupMapping`() {
        val mapping =
            CurrencyLookupMapping(
                id = FieldMappingId(Uuid.random()),
                fieldType = TransferField.CURRENCY,
                columnName = "Currency",
            )
        val mappings = mapOf(TransferField.CURRENCY to mapping)

        val json = FieldMappingJsonCodec.encode(mappings)
        val decoded = FieldMappingJsonCodec.decode(json)

        val decodedMapping = decoded[TransferField.CURRENCY]
        assertIs<CurrencyLookupMapping>(decodedMapping)
        assertEquals("Currency", decodedMapping.columnName)
    }

    @Test
    fun `encode and decode complete strategy mappings`() {
        val currencyId = CurrencyId(Uuid.random())
        val mappings =
            mapOf(
                TransferField.SOURCE_ACCOUNT to
                    HardCodedAccountMapping(
                        id = FieldMappingId(Uuid.random()),
                        fieldType = TransferField.SOURCE_ACCOUNT,
                        accountId = AccountId(1),
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
                        dateFormat = "yyyy-MM-dd",
                    ),
                TransferField.DESCRIPTION to
                    DirectColumnMapping(
                        id = FieldMappingId(Uuid.random()),
                        fieldType = TransferField.DESCRIPTION,
                        columnName = "Memo",
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
                        currencyId = currencyId,
                    ),
            )

        val json = FieldMappingJsonCodec.encode(mappings)
        val decoded = FieldMappingJsonCodec.decode(json)

        assertEquals(6, decoded.size)
        assertIs<HardCodedAccountMapping>(decoded[TransferField.SOURCE_ACCOUNT])
        assertIs<AccountLookupMapping>(decoded[TransferField.TARGET_ACCOUNT])
        assertIs<DateTimeParsingMapping>(decoded[TransferField.TIMESTAMP])
        assertIs<DirectColumnMapping>(decoded[TransferField.DESCRIPTION])
        assertIs<AmountParsingMapping>(decoded[TransferField.AMOUNT])
        assertIs<HardCodedCurrencyMapping>(decoded[TransferField.CURRENCY])
    }

    @Test
    fun `encode and decode identification columns`() {
        val columns = setOf("Date", "Description", "Amount", "Payee")

        val json = FieldMappingJsonCodec.encodeColumns(columns)
        val decoded = FieldMappingJsonCodec.decodeColumns(json)

        assertEquals(columns, decoded)
    }

    @Test
    fun `encode and decode empty identification columns`() {
        val columns = emptySet<String>()

        val json = FieldMappingJsonCodec.encodeColumns(columns)
        val decoded = FieldMappingJsonCodec.decodeColumns(json)

        assertEquals(columns, decoded)
    }

    @Test
    fun `encoded JSON is valid and parseable`() {
        val mapping =
            HardCodedAccountMapping(
                id = FieldMappingId(Uuid.random()),
                fieldType = TransferField.SOURCE_ACCOUNT,
                accountId = AccountId(42),
            )
        val mappings = mapOf(TransferField.SOURCE_ACCOUNT to mapping)

        val json = FieldMappingJsonCodec.encode(mappings)

        assertTrue(json.contains("SOURCE_ACCOUNT"))
        assertTrue(json.contains("HardCodedAccountMapping"))
        assertTrue(json.contains("42"))
    }
}

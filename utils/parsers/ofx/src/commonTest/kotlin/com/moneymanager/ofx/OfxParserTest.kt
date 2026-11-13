package com.moneymanager.ofx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfxParserTest {
    private val parser = OfxParser()

    @Test
    fun parse_sgmlHeaderCreditCardStatement_readsTransactions() {
        val ofx =
            """
            OFXHEADER:100
            DATA:OFXSGML
            VERSION:102
            SECURITY:NONE
            ENCODING:USASCII
            CHARSET:1252
            COMPRESSION:NONE
            OLDFILEUID:NONE
            NEWFILEUID:NONE

            <OFX>
              <SIGNONMSGSRSV1>
                <SONRS>
                  <STATUS><CODE>0</CODE><SEVERITY>INFO</SEVERITY></STATUS>
                  <DTSERVER>20190801000000</DTSERVER>
                  <LANGUAGE>ENG</LANGUAGE>
                </SONRS>
              </SIGNONMSGSRSV1>
              <CREDITCARDMSGSRSV1>
                <CCSTMTTRNRS>
                  <TRNUID>1</TRNUID>
                  <CCSTMTRS>
                    <CURDEF>GBP</CURDEF>
                    <CCACCTFROM><ACCTID>1234</ACCTID></CCACCTFROM>
                    <BANKTRANLIST>
                      <DTSTART>20190701000000</DTSTART>
                      <DTEND>20190731000000</DTEND>
                      <STMTTRN>
                        <TRNTYPE>DEBIT</TRNTYPE>
                        <DTPOSTED>20190722000000</DTPOSTED>
                        <TRNAMT>-6.99</TRNAMT>
                        <FITID>20190722000000001</FITID>
                        <NAME>STREAMING SERVICE</NAME>
                        <CARD>1234</CARD>
                        <MEMO>1234</MEMO>
                      </STMTTRN>
                      <STMTTRN>
                        <TRNTYPE>CREDIT</TRNTYPE>
                        <DTPOSTED>20190725000000</DTPOSTED>
                        <TRNAMT>120.00</TRNAMT>
                        <FITID>20190725000000001</FITID>
                        <NAME>Payment By Direct Debit</NAME>
                      </STMTTRN>
                    </BANKTRANLIST>
                    <LEDGERBAL><BALAMT>-50.00</BALAMT><DTASOF>20190731000000</DTASOF></LEDGERBAL>
                  </CCSTMTRS>
                </CCSTMTTRNRS>
              </CREDITCARDMSGSRSV1>
            </OFX>
            """.trimIndent()

        val document = parser.parse(ofx)

        assertNull(document.institution)
        val statement = document.statements.single()
        assertEquals("GBP", statement.currency)
        assertEquals("1234", statement.accountId)
        assertNull(statement.bankId)
        assertEquals("CREDITCARD", statement.accountType)
        assertEquals(2, statement.transactions.size)
        val spend = statement.transactions[0]
        assertEquals("DEBIT", spend.type)
        assertEquals("20190722000000", spend.datePosted)
        assertEquals("-6.99", spend.amount)
        assertEquals("20190722000000001", spend.fitId)
        assertEquals("STREAMING SERVICE", spend.name)
        assertEquals("1234", spend.memo)
        assertEquals("Payment By Direct Debit", statement.transactions[1].name)
        assertNull(statement.transactions[1].memo)
    }

    @Test
    fun parse_xmlHeaderWithCrlf_readsTransactions() {
        val ofx =
            listOf(
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>",
                "<?OFX OFXHEADER=\"200\" VERSION=\"202\" SECURITY=\"NONE\" OLDFILEUID=\"NONE\" NEWFILEUID=\"NONE\"?>",
                "<OFX>",
                "  <CREDITCARDMSGSRSV1><CCSTMTTRNRS><CCSTMTRS>",
                "    <CURDEF>GBP</CURDEF>",
                "    <CCACCTFROM><ACCTID>1234</ACCTID></CCACCTFROM>",
                "    <BANKTRANLIST>",
                "      <STMTTRN>",
                "        <TRNTYPE>DEBIT</TRNTYPE>",
                "        <DTPOSTED>20210402000000</DTPOSTED>",
                "        <TRNAMT>-10.00</TRNAMT>",
                "        <FITID>20210402000000002</FITID>",
                "        <NAME>FISH &amp; CHIPS</NAME>",
                "        <MEMO>1234/8.64 POUND STERLING GREAT BRITAIN</MEMO>",
                "      </STMTTRN>",
                "    </BANKTRANLIST>",
                "  </CCSTMTRS></CCSTMTTRNRS></CREDITCARDMSGSRSV1>",
                "</OFX>",
            ).joinToString("\r\n")

        val transaction =
            parser
                .parse(ofx)
                .statements
                .single()
                .transactions
                .single()

        assertEquals("FISH & CHIPS", transaction.name)
        assertEquals("1234/8.64 POUND STERLING GREAT BRITAIN", transaction.memo)
        assertEquals("-10.00", transaction.amount)
    }

    @Test
    fun parse_singleLineXmlWithInstitution_readsInstitutionAndFields() {
        val ofx =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><?OFX OFXHEADER=\"200\" VERSION=\"211\"?>" +
                "<OFX><SIGNONMSGSRSV1><SONRS><FI><ORG>AMEX</ORG><FID>3101</FID></FI></SONRS></SIGNONMSGSRSV1>" +
                "<CREDITCARDMSGSRSV1><CCSTMTTRNRS><CCSTMTRS><CURDEF>USD</CURDEF>" +
                "<CCACCTFROM><ACCTID>XXXX-12345</ACCTID></CCACCTFROM><BANKTRANLIST>" +
                "<STMTTRN><TRNTYPE>DEBIT</TRNTYPE><DTPOSTED>20240402000000.000[-7:MST]</DTPOSTED>" +
                "<DTUSER>20240401000000.000[-7:MST]</DTUSER><TRNAMT>-25.50</TRNAMT>" +
                "<FITID>AT240930001000010012345</FITID><REFNUM>320240930001</REFNUM>" +
                "<NAME>CORNER SHOP</NAME></STMTTRN></BANKTRANLIST></CCSTMTRS></CCSTMTTRNRS></CREDITCARDMSGSRSV1></OFX>"

        val document = parser.parse(ofx)

        assertEquals("AMEX", document.institution)
        val transaction =
            document.statements
                .single()
                .transactions
                .single()
        assertEquals("20240402000000.000[-7:MST]", transaction.datePosted)
        assertEquals("20240401000000.000[-7:MST]", transaction.dateUser)
        assertEquals("320240930001", transaction.refNum)
        assertEquals("CORNER SHOP", transaction.name)
    }

    @Test
    fun parse_sgmlUnclosedLeavesAndBankStatements_readsEveryStatement() {
        val ofx =
            """
            OFXHEADER:100
            DATA:OFXSGML

            <OFX>
            <BANKMSGSRSV1>
            <STMTTRNRS>
            <STMTRS>
            <CURDEF>EUR
            <BANKACCTFROM>
            <BANKID>123456
            <ACCTID>98765432
            <ACCTTYPE>SAVINGS
            </BANKACCTFROM>
            <BANKTRANLIST>
            <STMTTRN>
            <TRNTYPE>CHECK
            <DTPOSTED>20200105
            <TRNAMT>-42.10
            <FITID>A1
            <CHECKNUM>1001
            <PAYEE>
            <NAME>LANDLORD
            <ADDR1>1 HIGH ST
            </PAYEE>
            <CURRENCY>
            <CURRATE>1.1
            <CURSYM>USD
            </CURRENCY>
            </STMTTRN>
            </BANKTRANLIST>
            </STMTRS>
            </STMTTRNRS>
            <STMTTRNRS>
            <STMTRS>
            <CURDEF>EUR
            <BANKACCTFROM>
            <BANKID>123456
            <ACCTID>11112222
            <ACCTTYPE>CHECKING
            </BANKACCTFROM>
            <BANKTRANLIST>
            </BANKTRANLIST>
            </STMTRS>
            </STMTTRNRS>
            </BANKMSGSRSV1>
            </OFX>
            """.trimIndent()

        val statements = parser.parse(ofx).statements

        assertEquals(2, statements.size)
        val savings = statements[0]
        assertEquals("123456", savings.bankId)
        assertEquals("98765432", savings.accountId)
        assertEquals("SAVINGS", savings.accountType)
        val check = savings.transactions.single()
        assertEquals("CHECK", check.type)
        assertEquals("1001", check.checkNum)
        assertEquals("LANDLORD", check.name)
        assertEquals("USD", check.currency)
        assertEquals("1.1", check.currencyRate)
        assertNull(check.originalCurrency)
        assertEquals("CHECKING", statements[1].accountType)
        assertTrue(statements[1].transactions.isEmpty())
    }

    @Test
    fun parse_noStatements_returnsEmptyDocument() {
        assertTrue(parser.parse("").statements.isEmpty())
        assertTrue(parser.parse("not an ofx file").statements.isEmpty())
    }

    @Test
    fun decodeOfxBytes_declaredWindows1252_decodesPoundAndEuro() {
        val text = "CHARSET:1252\n\n<OFX><NAME>COST "
        val bytes = text.encodeToByteArray() + byteArrayOf(0xA3.toByte(), '5'.code.toByte(), ' '.code.toByte(), 0x80.toByte())

        assertEquals("$text£5 €", decodeOfxBytes(bytes))
    }

    @Test
    fun decodeOfxBytes_invalidUtf8WithoutDeclaration_fallsBackToWindows1252() {
        val bytes = "<NAME>CAF".encodeToByteArray() + byteArrayOf(0xE9.toByte())

        assertEquals("<NAME>CAFé", decodeOfxBytes(bytes))
    }

    @Test
    fun decodeOfxBytes_utf8WithByteOrderMark_dropsTheMark() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "<OFX>£".encodeToByteArray()

        assertEquals("<OFX>£", decodeOfxBytes(bytes))
    }

    @Test
    fun parseDateTime_variants() {
        assertEquals(OfxDateTime("2019-07-22", "00:00:00", null), OfxDateTime.parse("20190722000000"))
        assertEquals(OfxDateTime("2020-01-05", null, null), OfxDateTime.parse("20200105"))
        assertEquals(OfxDateTime("2024-04-02", "13:45:01", "-07:00"), OfxDateTime.parse("20240402134501.000[-7:MST]"))
        assertEquals(OfxDateTime("2024-04-02", "00:00:00", "+05:30"), OfxDateTime.parse("20240402000000[5.5]"))
        assertNull(OfxDateTime.parse("2024"))
    }
}

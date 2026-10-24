package com.moneymanager.query

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SingleStatementTest {
    @Test
    fun trailingSemicolonsAndBlanksAreDropped() {
        assertEquals("SELECT 1", singleStatement("  SELECT 1 ;\n"))
        assertEquals("SELECT 1", singleStatement("SELECT 1;;"))
        assertEquals("SELECT 1", singleStatement("SELECT 1; -- done\n/* really */"))
    }

    @Test
    fun semicolonsInsideQuotesAndCommentsAreText() {
        val sql = "SELECT 'a;b', \"c;d\", `e;f`, [g;h] -- i;j\n/* k;l */ FROM t"
        assertEquals(sql, singleStatement(sql))
        assertEquals("SELECT 'it''s; fine'", singleStatement("SELECT 'it''s; fine';"))
    }

    @Test
    fun aSecondStatementIsRefused() {
        assertFailsWith<InvalidSqlException> { singleStatement("SELECT 1; DELETE FROM asset") }
        assertFailsWith<InvalidSqlException> { singleStatement("SELECT 1;'x'") }
        assertFailsWith<InvalidSqlException> { singleStatement("SELECT 1; /* hidden */ DROP TABLE asset") }
    }

    @Test
    fun nothingToRunIsRefused() {
        assertFailsWith<InvalidSqlException> { singleStatement("") }
        assertFailsWith<InvalidSqlException> { singleStatement(" ; -- nothing") }
    }
}

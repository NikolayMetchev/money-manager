package com.moneymanager.apiimporter

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.accountmapping.AccountMapping
import com.moneymanager.regex.compileRegex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.uuid.Uuid

class MerchantMappingsTest {
    private val revolut = AccountId(13)
    private val curve = AccountId(208)

    private fun mapping(
        id: Long,
        pattern: String,
        accountId: AccountId,
        strategyId: CsvImportStrategyId? = null,
    ): AccountMapping {
        val now = Clock.System.now()
        return AccountMapping(id, strategyId, compileRegex(pattern, RegexOption.IGNORE_CASE), accountId, now, now)
    }

    @Test
    fun `a mapping on the stripped merchant names its account`() {
        val mappings = MerchantMappings(listOf(mapping(1, "^\\QRevolut**4647* Lon London\\E$", revolut)), emptySet())

        assertEquals(revolut, mappings.find("Revolut**4647* Lon London", "CRV*Revolut**4647* Lon London"))
    }

    @Test
    fun `a mapping on the whole description applies when the stripped merchant matches none`() {
        val mappings = MerchantMappings(listOf(mapping(1, "^CRV\\*Revolut", revolut)), emptySet())

        assertEquals(revolut, mappings.find("Revolut**4647* Lon London", "CRV*Revolut**4647* Lon London"))
    }

    @Test
    fun `no mapping matching leaves the merchant to be created`() {
        val mappings = MerchantMappings(listOf(mapping(1, "^Amazon", revolut)), emptySet())

        assertNull(mappings.find("Tesco", "CRV*Tesco"))
    }

    @Test
    fun `a mapping onto a conduit of the chain is ignored`() {
        val mappings = MerchantMappings(listOf(mapping(1, "Revolut", curve)), setOf(curve))

        assertNull(mappings.find("Revolut**4647* Lon London", "CRV*Revolut**4647* Lon London"))
    }

    @Test
    fun `the first matching mapping wins`() {
        val other = AccountId(99)
        val mappings = MerchantMappings(listOf(mapping(1, "Revolut", revolut), mapping(2, "Revolut", other)), emptySet())

        assertEquals(revolut, mappings.find("Revolut top-up"))
    }

    @Test
    fun `a strategy scoped mapping is never part of the global set`() {
        // The pipeline filters to global mappings before building this; the type keeps them out of find only by that filter.
        val scoped = mapping(1, "Revolut", revolut, strategyId = CsvImportStrategyId(Uuid.random()))
        val globals = listOf(scoped).filter { it.strategyId == null }

        assertNull(MerchantMappings(globals, emptySet()).find("Revolut top-up"))
    }
}

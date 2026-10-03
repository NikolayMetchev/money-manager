package com.moneymanager.ui.screens.apistrategy

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the display labels the API strategy audit diff derives from flattened config JSON paths.
 * The diff itself covers every config field generically, so these only need to stay readable — but a
 * regression here (a section prefix lost, a user-named map key mangled) is invisible without a test.
 */
class ApiStrategyAuditLabelTest {
    @Test
    fun `top-level fields read as prose with acronyms restored`() {
        assertEquals("Base URL", labelForPath("baseUrl"))
        assertEquals("Token page URL", labelForPath("tokenPageUrl"))
        assertEquals("Max rate limit retries", labelForPath("maxRateLimitRetries"))
        assertEquals("Person external ID attribute", labelForPath("personExternalIdAttribute"))
    }

    @Test
    fun `a section prefixes every field beneath it`() {
        assertEquals("Account ID field", labelForPath("accounts.mappings.idField"))
        assertEquals("Account description field", labelForPath("accounts.mappings.descriptionField"))
        assertEquals("Account name", labelForPath("accounts.name"))
        assertEquals("Accounts endpoint path", labelForPath("accounts.endpoint.path"))
        assertEquals("Accounts endpoint pagination paging param", labelForPath("accounts.endpoint.pagination.paging.param"))
        assertEquals("Ancestor endpoint #2 path", labelForPath("accounts.ancestorEndpoints[1].path"))
        assertEquals(
            "Data endpoint #1 transaction mappings local currency field",
            labelForPath("dataEndpoints[0].transactionMappings.localCurrencyField"),
        )
        assertEquals("Request signing algorithm", labelForPath("requestSigning.algorithm"))
    }

    @Test
    fun `the people section contributes no prefix so its labels stay as they read`() {
        assertEquals("Counterparty object field", labelForPath("peopleMappings.counterpartyObjectField"))
        assertEquals("Counterparty name field", labelForPath("peopleMappings.counterpartyNameField"))
        assertEquals(
            "Personal beneficiary account type value",
            labelForPath("peopleMappings.personalBeneficiaryAccountTypeValue"),
        )
    }

    @Test
    fun `list entries are numbered from one`() {
        assertEquals("Data endpoint #1 kind", labelForPath("dataEndpoints[0].kind"))
        assertEquals("Data endpoint #3 endpoint path", labelForPath("dataEndpoints[2].endpoint.path"))
        assertEquals("Connect instructions #2", labelForPath("connectInstructions[1]"))
        assertEquals(
            "Internal transfer reconcile bridges #1 other account name",
            labelForPath("internalTransferReconcile.bridges[0].otherAccountName"),
        )
    }

    @Test
    fun `user-named map keys are left exactly as typed`() {
        assertEquals("Account custom fields Monzo category", labelForPath("accounts.mappings.customFields.Monzo category"))
        assertEquals("Asset codes aliases XXBT", labelForPath("assetCodes.aliases.XXBT"))
        assertEquals("Minor unit divisor overrides GBP", labelForPath("minorUnitDivisorOverrides.GBP"))
    }

    @Test
    fun `a config field with no section entry still gets a readable label`() {
        assertEquals("Some new setting field", labelForPath("someNewSetting.field"))
    }
}

package com.moneymanager.database.repository

import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.model.passthrough.PassThroughAccountId
import com.moneymanager.domain.model.passthrough.PassThroughRule
import com.moneymanager.importengineapi.createPassThroughAccount
import com.moneymanager.importengineapi.deletePassThroughAccount
import com.moneymanager.importengineapi.updatePassThroughAccount
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals

class PassThroughAccountRepositoryImplTest : DbTest() {
    private val curveRules =
        listOf(
            PassThroughRule(detectionPattern = "(?i)^CRV\\*", merchantPattern = "(?i)^CRV\\*\\s*(.+?)(?:\\s{2,}.*)?$"),
            PassThroughRule(detectionPattern = "(?i)^CURVE ", merchantPattern = "(?i)^CURVE (.+)$", merchantTemplate = "$0"),
        )

    private val curve = PassThroughAccount(id = PassThroughAccountId(0), name = "Curve", conduitAccountName = "Curve", rules = curveRules)

    private val paypal =
        PassThroughAccount(
            id = PassThroughAccountId(0),
            name = "PayPal",
            conduitAccountName = "PayPal",
            rules = listOf(PassThroughRule(detectionPattern = "(?i)^PAYPAL \\*", merchantPattern = "(?i)^PAYPAL \\*(.+)$")),
        )

    private suspend fun accounts() = repositories.passThroughAccountRepository.getAll().first()

    private suspend fun create(account: PassThroughAccount): PassThroughAccount {
        repositories.importEngine.createPassThroughAccount(account)
        return accounts().single { it.name == account.name }
    }

    @Test
    fun `an account reads back with its rules in order`() =
        runDbTest {
            val curveId = create(curve).id
            val paypalId = create(paypal).id

            assertEquals(listOf(curve.copy(id = curveId), paypal.copy(id = paypalId)), accounts())
            assertEquals(curve.copy(id = curveId), repositories.passThroughAccountRepository.getById(curveId).first())
        }

    @Test
    fun `updating an account replaces its rules`() =
        runDbTest {
            val created = create(curve)
            val updated = created.copy(rules = curveRules.reversed() + PassThroughRule("(?i)^CRV ", "(?i)^CRV (.+)$"))
            repositories.importEngine.updatePassThroughAccount(updated)

            assertEquals(listOf(updated), accounts())
        }

    @Test
    fun `deleting an account leaves the others whole`() =
        runDbTest {
            val created = create(curve)
            val other = create(paypal)
            repositories.importEngine.deletePassThroughAccount(created.id)

            assertEquals(listOf(other), accounts())
        }
}

package com.moneymanager.ui.screens.currencies

import androidx.compose.runtime.Composable
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyAuditEntry
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.repository.AuditReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.ui.audit.AuditField
import com.moneymanager.ui.audit.AuditRevisionMeta
import com.moneymanager.ui.audit.AuditScreen
import com.moneymanager.ui.audit.AuditScreenData
import com.moneymanager.ui.audit.FlatEntityAuditDiffCard
import com.moneymanager.ui.audit.computeFlatEntityAuditDiffs
import kotlinx.coroutines.flow.first

private val currencyAuditFields =
    listOf(
        AuditField<CurrencyAuditEntry, Currency>("Code", fromEntry = { it.code }, fromCurrent = { it.code }),
        AuditField<CurrencyAuditEntry, Currency>("Name", fromEntry = { it.name }, fromCurrent = { it.name }),
        AuditField<CurrencyAuditEntry, Currency>(
            "Scale Factor",
            fromEntry = { it.scaleFactor.toString() },
            fromCurrent = { it.scaleFactor.toString() },
        ),
    )

@Composable
fun CurrencyAuditScreen(
    currencyId: CurrencyId,
    auditRepository: AuditReadRepository,
    currencyRepository: CurrencyReadRepository,
    onBack: () -> Unit,
) {
    AuditScreen(
        defaultTitle = "Currency Audit: $currencyId",
        entityTypeName = "currency",
        loadKey = currencyId,
        loadData = {
            val entries = auditRepository.getAuditHistoryForCurrency(currencyId)
            val currentCurrency = currencyRepository.getCurrencyById(currencyId).first()
            val diffs =
                computeFlatEntityAuditDiffs(entries, currentCurrency, currencyAuditFields) { entry ->
                    AuditRevisionMeta(entry.id, entry.auditTimestamp, entry.auditType, entry.revisionId, entry.source)
                }
            AuditScreenData(
                title = "Currency Audit: ${currentCurrency?.code ?: currencyId}",
                diffs = diffs,
            )
        },
        diffKey = { it.id },
        onBack = onBack,
        diffCard = { diff -> FlatEntityAuditDiffCard(diff) },
    )
}

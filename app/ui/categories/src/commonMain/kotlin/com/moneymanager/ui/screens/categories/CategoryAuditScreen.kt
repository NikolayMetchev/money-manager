package com.moneymanager.ui.screens.categories

import androidx.compose.runtime.Composable
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.CategoryAuditEntry
import com.moneymanager.domain.repository.AuditReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.ui.audit.AuditField
import com.moneymanager.ui.audit.AuditRevisionMeta
import com.moneymanager.ui.audit.AuditScreen
import com.moneymanager.ui.audit.AuditScreenData
import com.moneymanager.ui.audit.FlatEntityAuditDiffCard
import com.moneymanager.ui.audit.computeFlatEntityAuditDiffs
import kotlinx.coroutines.flow.first

@Composable
fun CategoryAuditScreen(
    categoryId: Long,
    auditRepository: AuditReadRepository,
    categoryRepository: CategoryReadRepository,
    onBack: () -> Unit,
) {
    AuditScreen(
        defaultTitle = "Category Audit: $categoryId",
        entityTypeName = "category",
        loadKey = categoryId,
        loadData = {
            val entries = auditRepository.getAuditHistoryForCategory(categoryId)
            val allCategories = categoryRepository.getAllCategories().first()
            val currentCategory = allCategories.find { it.id == categoryId }
            val diffs =
                computeFlatEntityAuditDiffs(entries, currentCategory, categoryAuditFields(allCategories)) { entry ->
                    AuditRevisionMeta(entry.id, entry.auditTimestamp, entry.auditType, entry.revisionId, entry.source)
                }
            AuditScreenData(
                title = "Category Audit: ${currentCategory?.name ?: categoryId}",
                diffs = diffs,
            )
        },
        diffKey = { it.id },
        onBack = onBack,
        diffCard = { diff -> FlatEntityAuditDiffCard(diff) },
    )
}

/**
 * An audit row records the parent's name alongside its id, but a row written before a later rename
 * carries the stale name — and the live category carries none at all — so [allCategories] supplies
 * the name whenever the row does not.
 */
private fun categoryAuditFields(allCategories: List<Category>): List<AuditField<CategoryAuditEntry, Category>> {
    fun parentDisplay(
        parentId: Long?,
        parentName: String?,
    ): String =
        when {
            parentId == null -> "(Top Level)"
            else -> parentName ?: allCategories.find { it.id == parentId }?.name ?: "ID: $parentId"
        }

    return listOf(
        AuditField("Name", fromEntry = { it.name }, fromCurrent = { it.name }),
        AuditField(
            "Parent",
            fromEntry = { parentDisplay(it.parentId, it.parentName) },
            fromCurrent = { parentDisplay(it.parentId, null) },
        ),
    )
}

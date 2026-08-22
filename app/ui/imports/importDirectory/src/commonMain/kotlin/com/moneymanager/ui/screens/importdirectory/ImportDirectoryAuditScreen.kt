package com.moneymanager.ui.screens.importdirectory

import androidx.compose.runtime.Composable
import com.moneymanager.domain.model.ImportDirectoryAuditEntry
import com.moneymanager.domain.model.ImportDirectoryId
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.repository.AuditReadRepository
import com.moneymanager.domain.repository.ImportDirectoryReadRepository
import com.moneymanager.ui.audit.AuditScreen
import com.moneymanager.ui.audit.AuditScreenData
import com.moneymanager.ui.audit.NamedConfigAuditDiffCard
import com.moneymanager.ui.audit.NamedConfigRevision
import com.moneymanager.ui.audit.computeNamedConfigAuditDiffs
import kotlinx.coroutines.flow.first

@Composable
fun ImportDirectoryAuditScreen(
    directoryId: ImportDirectoryId,
    auditRepository: AuditReadRepository,
    importDirectoryRepository: ImportDirectoryReadRepository,
    onBack: () -> Unit,
) {
    AuditScreen(
        defaultTitle = "Import Directory Audit: $directoryId",
        entityTypeName = "import directory",
        loadKey = directoryId,
        loadData = {
            val entries = auditRepository.getAuditHistoryForImportDirectory(directoryId)
            val current = importDirectoryRepository.getDirectoryById(directoryId).first()
            AuditScreenData(
                title = "Import Directory Audit: ${current?.name ?: directoryId}",
                diffs =
                    computeNamedConfigAuditDiffs(
                        revisions = entries.map { it.toRevision() },
                        currentName = current?.name,
                        currentConfig = current?.flatten(),
                    ),
            )
        },
        diffKey = { it.id },
        onBack = onBack,
        diffCard = { diff -> NamedConfigAuditDiffCard(diff) },
    )
}

private fun ImportDirectoryAuditEntry.toRevision() =
    NamedConfigRevision(
        id = id,
        auditTimestamp = auditTimestamp,
        auditType = auditType,
        revisionId = revisionId,
        name = name,
        config = flatten(),
        source = source,
    )

private fun ImportDirectory.flatten(): Map<String, String> =
    flattenDirectory(
        provider = provider.name,
        displayPath = displayPath,
        folderRef = folderRef,
        topLevel = topLevel,
        excluded = excluded,
        parent = parentId?.id?.toString(),
        account = accountId?.id?.toString(),
    )

private fun ImportDirectoryAuditEntry.flatten(): Map<String, String> =
    flattenDirectory(
        provider = providerType,
        displayPath = displayPath,
        folderRef = folderRef,
        topLevel = topLevel,
        excluded = excluded,
        parent = parentId?.id?.toString(),
        account = accountId?.id?.toString(),
    )

private fun flattenDirectory(
    provider: String,
    displayPath: String?,
    folderRef: String,
    topLevel: Boolean,
    excluded: Boolean,
    parent: String?,
    account: String?,
): Map<String, String> =
    buildMap {
        put("Provider", provider)
        put("Folder", displayPath ?: folderRef)
        put("Folder ref", folderRef)
        put("Top-level", topLevel.toString())
        put("Excluded", excluded.toString())
        parent?.let { put("Parent", it) }
        account?.let { put("Account", it) }
    }

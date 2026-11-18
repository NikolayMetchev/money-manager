package com.moneymanager.domain.repository.write

import com.moneymanager.domain.model.observation.ImportGroupId
import com.moneymanager.domain.model.observation.ImportGroupKey
import com.moneymanager.domain.model.observation.ImportRecordId
import com.moneymanager.domain.model.observation.ImportRecordKey
import com.moneymanager.domain.repository.ImportRecordReadRepository

interface ImportRecordWriteRepository : ImportRecordReadRepository {
    /** Records [key] with [contentHash]; a key already recorded fails. */
    suspend fun insertRecord(
        key: ImportRecordKey,
        contentHash: String,
    ): ImportRecordId

    suspend fun writeRecords(
        inserts: Map<ImportRecordKey, String>,
        hashes: Map<ImportRecordId, String>,
    )

    /** Deletes a record and its group memberships. Its observations must be deleted first. */
    suspend fun deleteRecord(id: ImportRecordId)

    /** The group of [key], created when there is none. */
    suspend fun getOrCreateGroup(key: ImportGroupKey): ImportGroupId

    /** Makes [members] the group's records, replacing those it had. */
    suspend fun setGroupMembers(
        groupId: ImportGroupId,
        members: Set<ImportRecordId>,
    )

    /** Deletes a group. Its observations must be deleted first. */
    suspend fun deleteGroup(groupId: ImportGroupId)
}

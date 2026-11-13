package com.moneymanager.apiimport

import com.moneymanager.apiimporter.ApiSessionsArchive
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.repository.ApiImportStrategyReadRepository
import com.moneymanager.domain.repository.ApiSessionReadRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.tasks.TaskManager

/** Key of the one running import of an API sessions archive. */
const val API_SESSIONS_ARCHIVE_IMPORT_TASK_KEY = "api-sessions-archive-import"

/**
 * Restores the API sessions archive [content] as a task, so its progress shows while the (possibly large) file is
 * written. [onFinished] gets the summary, or why the file couldn't be imported. Returns null, starting nothing,
 * while another archive import is still running.
 */
fun TaskManager.startApiSessionsArchiveImport(
    content: String,
    deviceId: DeviceId,
    sessions: ApiSessionReadRepository,
    strategies: ApiImportStrategyReadRepository,
    engine: ImportEngine,
    onFinished: suspend (String) -> Unit,
): Long? =
    start(
        key = API_SESSIONS_ARCHIVE_IMPORT_TASK_KEY,
        title = "Import API sessions",
        initialDetail = "Reading the file",
    ) {
        val summary =
            ApiSessionsArchive.importAndDescribe(content, deviceId, sessions, strategies, engine, onProgress = { report(it) })
        onFinished(summary)
        summary
    }

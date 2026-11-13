package com.moneymanager.apiimport

import com.moneymanager.domain.model.ApiCredentialId
import com.moneymanager.domain.model.ApiSessionId

/** Key of the one "Download & import all" run. */
const val API_DOWNLOAD_IMPORT_ALL_TASK_KEY = "api-download-import-all"

/** Key of the one "Download all" run: every connection downloaded, nothing imported. */
const val API_DOWNLOAD_ALL_TASK_KEY = "api-download-all"

/** Key of the one "Import all" run: every never-imported session imported, nothing downloaded. */
const val API_IMPORT_ALL_TASK_KEY = "api-import-all"

/** Key of a credential's download, whether started on its own or as a child of a bulk run. */
fun apiDownloadTaskKey(credentialId: ApiCredentialId): String = "api-download-cred-${credentialId.id}"

/** Key of a session's import, whether started on its own or as a child of a bulk run. */
fun apiImportTaskKey(sessionId: ApiSessionId): String = "api-import-${sessionId.id}"

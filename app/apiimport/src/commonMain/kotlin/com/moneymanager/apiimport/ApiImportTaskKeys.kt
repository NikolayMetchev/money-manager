package com.moneymanager.apiimport

import com.moneymanager.domain.model.ApiCredentialId
import com.moneymanager.domain.model.ApiSessionId

/** Key of the one "Download & import all" run. */
const val API_DOWNLOAD_IMPORT_ALL_TASK_KEY = "api-download-import-all"

/** Key of a credential's download, whether started on its own or as a child of "Download & import all". */
fun apiDownloadTaskKey(credentialId: ApiCredentialId): String = "api-download-cred-${credentialId.id}"

/** Key of a session's import, whether started on its own or as a child of "Download & import all". */
fun apiImportTaskKey(sessionId: ApiSessionId): String = "api-import-${sessionId.id}"

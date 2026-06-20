package com.moneymanager.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportProgress
import com.moneymanager.importengineapi.ImportResult

/**
 * The [ImportEngine] for the active database, provided once at the app root
 * ([com.moneymanager.ui.MoneyManagerApp]). It is the single entry point for every edit (described
 * declaratively as an [ImportBatch]), so writes can be blocked centrally when editing is locked (e.g. a
 * cloud-backed database whose remote copy is ahead).
 *
 * Exposed as a composition local rather than threaded through every screen because the editing dialogs
 * are deeply nested (e.g. an account picker inside a transaction dialog). The default is a stub that
 * throws only if [ImportEngine.import] is *invoked* without a provider — so render-only previews/tests
 * work, while a test that exercises an edit must provide a real or fake engine via
 * `CompositionLocalProvider(LocalImportEngine provides …)`.
 */
val LocalImportEngine =
    staticCompositionLocalOf<ImportEngine> { UnprovidedImportEngine }

/** Stub used only when no engine is provided; [import] fails loudly so missing wiring is obvious. */
private object UnprovidedImportEngine : ImportEngine {
    override suspend fun import(
        batch: ImportBatch,
        onProgress: (suspend (ImportProgress) -> Unit)?,
        batchSize: Int,
    ): ImportResult = error("LocalImportEngine not provided — host this screen under MoneyManagerApp or provide it in tests")
}

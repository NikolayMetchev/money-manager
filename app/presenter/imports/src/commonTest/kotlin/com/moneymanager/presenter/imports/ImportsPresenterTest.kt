package com.moneymanager.presenter.imports

import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.moneymanager.credentialvault.testing.InMemoryLocalSettings
import com.moneymanager.credentialvault.testing.inMemoryCredentialVault
import com.moneymanager.domain.model.ImportDirectoryId
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.importdirectory.ImportDirectoryProvider
import com.moneymanager.files.writeText
import com.moneymanager.importengineapi.createImportDirectory
import com.moneymanager.importfilesource.ImportFileSource
import com.moneymanager.importfilesource.ImportFileSourceFactory
import com.moneymanager.importfilesource.localfolder.LocalFolderImportFileSource
import com.moneymanager.session.DatabaseSession
import com.moneymanager.session.DatabaseSessions
import com.moneymanager.session.SessionImports
import com.moneymanager.tasks.TaskManager
import com.moneymanager.tasks.TaskStatus
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.createTestDatabaseManager
import com.moneymanager.test.database.deleteTestDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

// Real time, not runTest's virtual clock: the database and the folder are read on real dispatchers.
class ImportsPresenterTest {
    @Test
    fun importingDirectoriesListsWhatNeedsAPersonAndKeepsItForTheNextScreen() =
        withImports { imports, manager, scope ->
            present(imports, manager, scope).test(timeout = TIMEOUT) {
                val idle = awaitItem()
                assertNull(idle.lastRun)
                idle.eventSink(ImportsEvent.Start(ImportKind.DIRECTORIES))
                val done = awaitUntil { it.lastRun != null && !it.running }
                assertEquals(listOf("noStrategy" to "unknown.csv"), done.attention.map { it.kind to it.subject })
                assertEquals(TaskStatus.SUCCEEDED, done.tasks.single { it.parentId == null }.status)
                cancelAndIgnoreRemainingEvents()
            }
            // A screen shown again (a fresh composition) finds the latest run.
            present(imports, manager, scope).test(timeout = TIMEOUT) {
                assertEquals(1, awaitItem().attention.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun importAllWithoutAnyApiStillImportsTheFolders() =
        withImports { imports, manager, scope ->
            present(imports, manager, scope).test(timeout = TIMEOUT) {
                awaitItem().eventSink(ImportsEvent.Start(ImportKind.ALL))
                val done = awaitUntil { it.lastRun != null && !it.running }
                assertEquals(listOf("noStrategy"), done.attention.map { it.kind })
                assertNull(done.lastRun?.apis)
                assertNull(done.error)
                cancelAndIgnoreRemainingEvents()
            }
        }

    private fun present(
        imports: SessionImports,
        manager: TaskManager,
        scope: CoroutineScope,
    ) = moleculeFlow(RecompositionMode.Immediate) { presentImports(manager, imports, scope) }

    private suspend fun ReceiveTurbine<ImportsModel>.awaitUntil(predicate: (ImportsModel) -> Boolean): ImportsModel {
        var item = awaitItem()
        while (!predicate(item)) item = awaitItem()
        return item
    }

    // A database with one local import folder holding a file no strategy matches.
    private fun withImports(test: suspend (SessionImports, TaskManager, CoroutineScope) -> Unit) =
        runBlocking {
            val location = createTestDatabaseLocation()
            val folder = Path(SystemTemporaryDirectory, "mm-imports-${Uuid.random()}")
            SystemFileSystem.createDirectories(folder)
            writeText(Path(folder, "unknown.csv"), "Foo,Bar\n1,2\n")
            val vault = inMemoryCredentialVault()
            val session = DatabaseSessions(createTestDatabaseManager(), InMemoryLocalSettings(), vault).open(location)
            val scope = CoroutineScope(SupervisorJob())
            try {
                session.addFolder(folder)
                test(SessionImports(session.services, vault, LocalFolders), TaskManager(scope), scope)
            } finally {
                scope.cancel()
                session.database.close()
                deleteTestDatabase(location)
                SystemFileSystem.list(folder).forEach { SystemFileSystem.delete(it) }
                SystemFileSystem.delete(folder)
            }
        }

    private suspend fun DatabaseSession.addFolder(folder: Path) {
        val now = Clock.System.now()
        importEngine.createImportDirectory(
            ImportDirectory(
                id = ImportDirectoryId(Uuid.random()),
                name = "Inbox",
                provider = ImportDirectoryProvider.LOCAL,
                folderRef = folder.toString(),
                deviceId = services.deviceId,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private object LocalFolders : ImportFileSourceFactory {
        override suspend fun create(directory: ImportDirectory): ImportFileSource = LocalFolderImportFileSource(directory.folderRef)
    }

    private companion object {
        val TIMEOUT = 30.seconds
    }
}

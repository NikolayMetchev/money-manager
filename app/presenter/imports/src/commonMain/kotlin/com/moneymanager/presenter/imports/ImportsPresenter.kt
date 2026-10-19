package com.moneymanager.presenter.imports

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.moneymanager.session.ApiImport
import com.moneymanager.session.DirectoryImport
import com.moneymanager.session.ImportAttention
import com.moneymanager.session.ImportRequest
import com.moneymanager.session.ImportRunOutcome
import com.moneymanager.session.SessionImports
import com.moneymanager.tasks.TaskManager
import com.moneymanager.tasks.TaskState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What an import run covers, as `mm import dirs|apis|all` does. */
enum class ImportKind {
    DIRECTORIES,
    APIS,
    ALL,
}

sealed interface ImportsEvent {
    /** Starts a run of [kind]; [forceFull] downloads every API connection's whole history again. */
    data class Start(
        val kind: ImportKind,
        val forceFull: Boolean = false,
    ) : ImportsEvent

    /** Cancels the running import's tasks. */
    data object Cancel : ImportsEvent
}

/**
 * What an imports screen shows. [tasks] is every task the manager holds, for a task panel; [lastRun] the latest
 * finished run, whose [attention] items only a person can resolve; [error] why the latest start failed.
 */
data class ImportsModel(
    val tasks: List<TaskState>,
    val running: Boolean,
    val lastRun: ImportRunOutcome?,
    val error: String?,
    val eventSink: (ImportsEvent) -> Unit,
) {
    val attention: List<ImportAttention> get() = lastRun?.needsAttention.orEmpty()
}

/**
 * Starts import runs on [imports] as tasks of [manager] and reports them. Runs are launched on [scope], which must
 * outlive the screen (a run keeps going when the person looks elsewhere), and the outcome is kept by [imports],
 * so a screen shown again finds the latest one.
 */
@Composable
fun presentImports(
    manager: TaskManager,
    imports: SessionImports,
    scope: CoroutineScope,
): ImportsModel {
    val tasks by manager.tasks.collectAsState()
    val running by imports.running.collectAsState()
    val lastRun by imports.lastRun.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }

    val eventSink: (ImportsEvent) -> Unit =
        remember(manager, imports, scope) {
            { event ->
                when (event) {
                    is ImportsEvent.Start -> {
                        error = null
                        scope.launch {
                            try {
                                imports.run(manager, event.toRequest())
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (expected: Exception) {
                                // The vault prompt declined, another run under way: the person is told and can try again.
                                error = expected.message ?: expected::class.simpleName
                            }
                        }
                    }

                    ImportsEvent.Cancel -> {
                        manager.tasks.value
                            .filter { it.parentId == null && it.isRunning }
                            .forEach { manager.cancel(it.id) }
                    }
                }
            }
        }

    return ImportsModel(tasks = tasks, running = running, lastRun = lastRun, error = error, eventSink = eventSink)
}

private fun ImportsEvent.Start.toRequest(): ImportRequest =
    when (kind) {
        ImportKind.DIRECTORIES -> ImportRequest(directories = DirectoryImport())

        ImportKind.APIS -> ImportRequest(apis = ApiImport(forceFull = forceFull))

        // As `mm import all`: a database without APIs, or with its vault locked, still imports its folders.
        ImportKind.ALL -> ImportRequest(directories = DirectoryImport(), apis = ApiImport(forceFull = forceFull, optional = true))
    }

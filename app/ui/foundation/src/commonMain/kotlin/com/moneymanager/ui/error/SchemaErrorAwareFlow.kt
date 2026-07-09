package com.moneymanager.ui.error

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import org.lighthousegames.logging.logging

private val logger = logging()

/**
 * Collects a Flow as Compose State while catching schema errors and reporting them globally.
 * If a schema error occurs, it's reported to GlobalSchemaErrorState and the initial value is returned.
 *
 * @param initial The initial value to use before the first emission and if a schema error occurs
 * @param databaseLocation The database location to report with the error
 * @return State containing the flow's current value or initial if error occurred
 */
@Composable
fun <T> Flow<T>.collectAsStateWithSchemaErrorHandling(
    initial: T,
    databaseLocation: String = "default",
): State<T> =
    produceState(initial, this) {
        catch { e ->
            if (SchemaErrorDetector.isSchemaError(e)) {
                logger.error(e) { "Schema error in Flow collection: ${e.message}" }
                GlobalSchemaErrorState.reportError(databaseLocation, e)
            } else {
                throw e
            }
        }.collect { value = it }
    }

/**
 * Remembers a repository [Flow] and collects it as Compose State (via
 * [collectAsStateWithSchemaErrorHandling]). Repository query builders return a **new** Flow instance
 * on every call; passing that straight into [collectAsStateWithSchemaErrorHandling] re-keys the
 * underlying `produceState` on each recomposition, cancelling and relaunching the collector — which
 * re-runs the SQL query and its Kotlin-side mapping every recomposition. Wrapping the flow in
 * [remember] here keeps the instance (and thus the subscription) stable; the flow still re-emits
 * reactively when its tables change.
 *
 * Pass any values the [flow] closes over as [keys] so the flow is rebuilt when they change (e.g. an
 * account id for a per-account query). Param-less flows need no keys.
 *
 * @param keys inputs the flow depends on; changing any rebuilds and re-subscribes the flow
 * @param initial value used before the first emission and if a schema error occurs
 * @param flow factory that builds the repository flow
 */
@Composable
fun <T> rememberFlowAsStateWithSchemaErrorHandling(
    vararg keys: Any?,
    initial: T,
    databaseLocation: String = "default",
    flow: () -> Flow<T>,
): State<T> = remember(*keys) { flow() }.collectAsStateWithSchemaErrorHandling(initial, databaseLocation)

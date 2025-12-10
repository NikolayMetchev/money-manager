package com.moneymanager.ui.error

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
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
): State<T> {
    return produceState(initial) {
        catch { e ->
            if (SchemaErrorDetector.isSchemaError(e)) {
                logger.error(e) { "Schema error in Flow collection: ${e.message}" }
                GlobalSchemaErrorState.reportError(databaseLocation, e)
            } else {
                throw e
            }
        }.collect { value = it }
    }
}

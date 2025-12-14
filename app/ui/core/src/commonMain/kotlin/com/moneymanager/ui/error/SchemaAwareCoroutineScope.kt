package com.moneymanager.ui.error

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.lighthousegames.logging.logging

private val logger = logging()

/**
 * A CoroutineExceptionHandler that catches schema errors and reports them to GlobalSchemaErrorState.
 * Non-schema errors are logged but not swallowed - they will still propagate.
 */
val SchemaAwareExceptionHandler: CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, throwable ->
        if (SchemaErrorDetector.isSchemaError(throwable)) {
            logger.error(throwable) { "Schema error caught by global handler: ${throwable.message}" }
            GlobalSchemaErrorState.reportError("default", throwable)
        } else {
            // Log non-schema errors but let them propagate through normal channels
            logger.error(throwable) { "Non-schema error in coroutine: ${throwable.message}" }
            // Re-throw to let it propagate to the thread's uncaught exception handler
            throw throwable
        }
    }

/**
 * CompositionLocal providing a CoroutineScope with schema error handling.
 * This scope includes a SupervisorJob and the SchemaAwareExceptionHandler.
 */
val LocalSchemaAwareScope =
    compositionLocalOf<CoroutineScope> {
        error("No SchemaAwareScope provided. Wrap your app with ProvideSchemaAwareScope.")
    }

/**
 * Provides a schema-aware CoroutineScope to the composition.
 * All child composables can access this scope via LocalSchemaAwareScope.current.
 */
@Composable
fun ProvideSchemaAwareScope(content: @Composable () -> Unit) {
    val parentScope = rememberCoroutineScope()
    val schemaAwareScope =
        remember(parentScope) {
            parentScope + SupervisorJob() + SchemaAwareExceptionHandler
        }

    DisposableEffect(Unit) {
        onDispose {
            schemaAwareScope.cancel()
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalSchemaAwareScope provides schemaAwareScope,
        content = content,
    )
}

/**
 * Returns a CoroutineScope with schema error handling.
 * Use this instead of rememberCoroutineScope() when you need schema-aware error handling.
 */
@Composable
fun rememberSchemaAwareCoroutineScope(): CoroutineScope {
    return LocalSchemaAwareScope.current
}

/**
 * A replacement for LaunchedEffect that catches schema errors and reports them globally.
 * Use this instead of LaunchedEffect when the block may throw schema errors.
 *
 * @param key1 A key that triggers re-execution when changed
 * @param block The suspend block to execute
 */
@Composable
fun SchemaAwareLaunchedEffect(
    key1: Any?,
    block: suspend CoroutineScope.() -> Unit,
) {
    val scope = LocalSchemaAwareScope.current
    LaunchedEffect(key1) {
        scope.launch {
            block()
        }.join()
    }
}

/**
 * A replacement for LaunchedEffect that catches schema errors and reports them globally.
 * Use this instead of LaunchedEffect when the block may throw schema errors.
 *
 * @param key1 First key that triggers re-execution when changed
 * @param key2 Second key that triggers re-execution when changed
 * @param block The suspend block to execute
 */
@Composable
fun SchemaAwareLaunchedEffect(
    key1: Any?,
    key2: Any?,
    block: suspend CoroutineScope.() -> Unit,
) {
    val scope = LocalSchemaAwareScope.current
    LaunchedEffect(key1, key2) {
        scope.launch {
            block()
        }.join()
    }
}

/**
 * A replacement for LaunchedEffect that catches schema errors and reports them globally.
 * Use this instead of LaunchedEffect when the block may throw schema errors.
 *
 * @param keys Keys that trigger re-execution when changed
 * @param block The suspend block to execute
 */
@Composable
fun SchemaAwareLaunchedEffect(
    vararg keys: Any?,
    block: suspend CoroutineScope.() -> Unit,
) {
    val scope = LocalSchemaAwareScope.current
    LaunchedEffect(*keys) {
        scope.launch {
            block()
        }.join()
    }
}

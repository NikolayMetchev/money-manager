package com.moneymanager.coroutines

import kotlinx.coroutines.CoroutineDispatcher

/**
 * The dispatcher for blocking I/O and for running many downloads at once: `Dispatchers.IO` where there is one (a
 * member on the JVM, an extension on native, so common code can't name it) and `Dispatchers.Default` in the browser,
 * which has one thread and nothing that blocks.
 */
expect val ioDispatcher: CoroutineDispatcher

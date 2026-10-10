package com.moneymanager

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import org.slf4j.LoggerFactory

/**
 * Routes Kermit into SLF4J, so desktop logs reach log4j's console and rolling-file appenders
 * (`log4j2.xml`) instead of Kermit's JVM default, a bare `println` to stdout.
 */
internal class Slf4jLogWriter : LogWriter() {
    override fun log(
        severity: Severity,
        message: String,
        tag: String,
        throwable: Throwable?,
    ) {
        val logger = LoggerFactory.getLogger(tag)
        when (severity) {
            Severity.Verbose -> logger.trace(message, throwable)
            Severity.Debug -> logger.debug(message, throwable)
            Severity.Info -> logger.info(message, throwable)
            Severity.Warn -> logger.warn(message, throwable)
            Severity.Error, Severity.Assert -> logger.error(message, throwable)
        }
    }
}

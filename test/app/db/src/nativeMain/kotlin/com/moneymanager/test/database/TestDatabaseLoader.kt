package com.moneymanager.test.database

import com.moneymanager.domain.model.DbLocation

/** Native test binaries have no classpath resources; only the JVM and Android UI tests copy fixture databases. */
actual fun copyDatabaseFromResources(resourcePath: String): DbLocation =
    throw UnsupportedOperationException("No test resources on native: $resourcePath")

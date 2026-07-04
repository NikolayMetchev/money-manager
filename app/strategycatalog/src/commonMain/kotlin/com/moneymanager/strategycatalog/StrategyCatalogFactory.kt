package com.moneymanager.strategycatalog

import io.ktor.client.HttpClient

/**
 * Builds the production controller. The engine-less [HttpClient] resolves a platform engine from the
 * runtime classpath (CIO on both JVM and Android via this module's runtimeOnly dependency), so DI
 * needs no Ktor types of its own.
 */
fun createStrategyCatalogController(): StrategyCatalogController = StrategyCatalogController(StrategyCatalogClient(HttpClient()))

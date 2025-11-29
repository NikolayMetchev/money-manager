package com.moneymanager.di

expect class AppComponentParams

/**
 * Creates AppComponentParams for testing.
 * On JVM, returns an empty params.
 * On Android, uses ApplicationProvider to get test context.
 */
expect fun createTestAppComponentParams(): AppComponentParams

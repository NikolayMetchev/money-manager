package com.moneymanager.di

import com.moneymanager.domain.model.AppVersion

/**
 * Platform-specific version reading.
 * Each platform implements this to read the VERSION file from its appropriate location.
 */
expect fun readAppVersion(): AppVersion

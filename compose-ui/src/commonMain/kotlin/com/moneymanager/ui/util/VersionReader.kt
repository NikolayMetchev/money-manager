package com.moneymanager.ui.util

/**
 * Reads the application version from the VERSION file bundled in resources.
 * Returns "Unknown" if the version cannot be read.
 */
expect fun readAppVersion(): String

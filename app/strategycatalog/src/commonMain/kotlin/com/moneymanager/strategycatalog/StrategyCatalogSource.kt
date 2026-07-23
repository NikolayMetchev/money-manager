package com.moneymanager.strategycatalog

/**
 * Where the catalog's manifest and per-artifact JSON files come from. The published GitHub Pages site
 * ([StrategyCatalogClient]) is the production source; a local directory
 * (`com.moneymanager.strategycatalog.LocalDirectoryStrategyCatalogSource`) lets a developer point the
 * catalog at a freshly generated `strategy-library/` folder without publishing it first.
 */
interface StrategyCatalogSource {
    suspend fun fetchManifest(): CatalogManifest

    suspend fun fetchArtifact(fileName: String): String
}

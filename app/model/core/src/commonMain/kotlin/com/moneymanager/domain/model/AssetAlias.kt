package com.moneymanager.domain.model

/** Another code for the asset with code [assetCode], both in upper case. A database's own; see [resolveAssetCode]. */
data class AssetAlias(
    val alias: String,
    val assetCode: String,
    /** Set by the catalog's crypto list, rather than by a person. */
    val published: Boolean = false,
)

/**
 * The code of the asset an import should book for [code] (already normalised to upper case). A database's own
 * [aliases] win, so a person can merge two assets the database already holds. Then an asset [isAsset] knows keeps its
 * own code, so a person can keep a catalog alias's code apart by creating that asset. Then the catalog's alias
 * ([CryptoRegistry.aliasTarget]). One hop only: an alias names an asset, never another alias.
 */
fun resolveAssetCode(
    code: String,
    aliases: Map<String, String>,
    isAsset: (String) -> Boolean,
): String = aliases[code] ?: code.takeIf(isAsset) ?: CryptoRegistry.aliasTarget(code) ?: code

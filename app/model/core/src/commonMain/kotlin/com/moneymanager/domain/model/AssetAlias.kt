package com.moneymanager.domain.model

/** Another code for the asset with code [assetCode], both in upper case. A database's own; see [resolveAssetCode]. */
data class AssetAlias(
    val alias: String,
    val assetCode: String,
    /** Who set it: a person, or one of the catalog's published lists. */
    val publisher: AliasPublisher = AliasPublisher.PERSON,
)

/** Who set an [AssetAlias]. Each published list's local copy is the aliases it set, so they must be told apart. */
enum class AliasPublisher(
    val id: Long,
) {
    PERSON(0),
    CRYPTO_LIST(1),
    CURRENCY_LIST(2),
    ;

    companion object {
        fun fromId(id: Long): AliasPublisher = entries.first { it.id == id }
    }
}

private val WHITESPACE = Regex("\\s+")

/**
 * The code of the asset an import should book for [code] (already normalised to upper case). A database's own
 * [aliases] win, so a person can merge two assets the database already holds. Then an asset [isAsset] knows keeps its
 * own code, so a person can keep a catalog alias's code apart by creating that asset. Then the catalog's alias
 * ([CryptoRegistry.aliasTarget]). One hop only: an alias names an asset, never another alias.
 *
 * Only when none of those applies, and the crypto catalog doesn't know [code] as a ticker either (so an unseen
 * `GOLD` stays a crypto rather than becoming XAU's "Gold"), is [code] read as an asset's name: [codeByName] maps an
 * installed asset's upper-case name to its code. A statement may follow the name with other words (Barclaycard's
 * `EURO FRANCE`, `US DOLLAR UNITED STATES`), so the longest leading run of words that is an alias, an asset's code,
 * a catalog alias or a name wins. Names that differ from an asset's own (`POUND STERLING`) are aliases.
 */
fun resolveAssetCode(
    code: String,
    aliases: Map<String, String>,
    codeByName: Map<String, String> = emptyMap(),
    isAsset: (String) -> Boolean,
): String {
    resolveKnownCode(code, aliases, isAsset)?.let { return it }
    if (CryptoRegistry.lookup(code) != null) return code
    val words = code.trim().split(WHITESPACE)
    codeByName[words.joinToString(" ")]?.let { return it }
    for (count in words.size - 1 downTo 1) {
        val leading = words.subList(0, count).joinToString(" ")
        (resolveKnownCode(leading, aliases, isAsset) ?: codeByName[leading])?.let { return it }
    }
    return code
}

private fun resolveKnownCode(
    code: String,
    aliases: Map<String, String>,
    isAsset: (String) -> Boolean,
): String? = aliases[code] ?: code.takeIf(isAsset) ?: CryptoRegistry.aliasTarget(code)

/** [assets] by upper-case name, for [resolveAssetCode]; the first asset of a name keeps it. */
fun codesByName(assets: Iterable<Pair<String, String>>): Map<String, String> {
    val byName = LinkedHashMap<String, String>()
    for ((code, name) in assets) {
        val key = name.trim().uppercase().replace(WHITESPACE, " ")
        if (key.isNotEmpty() && key !in byName) byName[key] = code.uppercase()
    }
    return byName
}

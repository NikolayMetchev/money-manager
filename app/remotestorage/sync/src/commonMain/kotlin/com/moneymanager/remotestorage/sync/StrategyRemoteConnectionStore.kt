package com.moneymanager.remotestorage.sync

import com.moneymanager.domain.strategy.StrategyKey
import com.moneymanager.localsettings.LocalSettings

/**
 * The per-artifact sync baseline: which remote file backs a [StrategyKey], the remote revision last
 * synced to (for remote-change detection), and the canonical content hash last synced (for local-change
 * detection without a network call).
 */
data class StrategySyncedBaseline(
    val remoteFileId: String,
    val syncedRevision: String?,
    val syncedHash: String,
)

/**
 * Persists the strategy library's own Google Drive connection (independent of any database binding) plus
 * a per-artifact sync baseline, in [LocalSettings]. Baselines are keyed by a short stable hash of the
 * artifact key so lookups never need to enumerate settings (the [LocalSettings] surface has no listing);
 * a stale baseline for a since-deleted artifact simply lingers (harmless — keep-forever library).
 */
class StrategyRemoteConnectionStore(
    private val localSettings: LocalSettings,
) {
    fun providerId(): String? = localSettings.getString(KEY_PROVIDER_ID)

    fun providerConfig(): String? = localSettings.getString(KEY_PROVIDER_CONFIG)

    fun isConnected(): Boolean = providerId() != null

    fun saveConnection(
        providerId: String,
        config: String?,
    ) {
        localSettings.putString(KEY_PROVIDER_ID, providerId)
        if (config != null) localSettings.putString(KEY_PROVIDER_CONFIG, config) else localSettings.remove(KEY_PROVIDER_CONFIG)
    }

    /** Disconnects the strategy library from remote storage (drops connection; baselines are left as-is). */
    fun clearConnection() {
        localSettings.remove(KEY_PROVIDER_ID)
        localSettings.remove(KEY_PROVIDER_CONFIG)
    }

    fun baseline(key: StrategyKey): StrategySyncedBaseline? {
        val raw = localSettings.getString(baselineKey(key)) ?: return null
        val parts = raw.split(FIELD_SEP)
        if (parts.size != BASELINE_FIELDS) return null
        val revision = parts[1].takeIf { it.isNotEmpty() }
        return StrategySyncedBaseline(remoteFileId = parts[0], syncedRevision = revision, syncedHash = parts[2])
    }

    fun putBaseline(
        key: StrategyKey,
        baseline: StrategySyncedBaseline,
    ) {
        val fields = listOf(baseline.remoteFileId, baseline.syncedRevision.orEmpty(), baseline.syncedHash)
        // Never persist a value that couldn't round-trip (separator collision) or that contains control
        // characters: on JVM, LocalSettings is java.util.prefs backed by prefs.xml, and a single
        // XML-illegal character makes the WHOLE node fail to flush — silently losing every setting
        // written that session. Skipping the baseline merely degrades to "never synced" on restart.
        if (fields.any { field -> field.contains(FIELD_SEP) || !field.isXmlSafe() }) return
        localSettings.putString(baselineKey(key), fields.joinToString(FIELD_SEP))
    }

    // NUL as the kind/name join separator can never collide with a real StrategyKind name or
    // strategy name, so no delimiter collision is possible.
    private fun baselineKey(key: StrategyKey): String = "$KEY_BASELINE_PREFIX${stableHash("${key.kind}\u0000${key.name}")}"

    private companion object {
        const val KEY_PROVIDER_ID = "strategySync.providerId"
        const val KEY_PROVIDER_CONFIG = "strategySync.providerConfig"

        // "h" since the canonical-hash algorithm gained MORE collection-order canonicalization (API
        // strategies' Sets/Maps/Lists, plus CSV's RowCondition/ContentMatchRule/CompanionTransactionRule
        // lists, none of which were sorted under "g"): baselines holding old-algorithm hashes must not be
        // read back (they'd misreport artifacts as locally changed/conflicting), so the prefix bump
        // orphans them and the never-synced content-compare path adopts fresh baselines without spurious
        // uploads. Old "strategySync.f."/"strategySync.g." keys linger harmlessly.
        // "i" since strategy configs carry a `configVersion` stamp and were reshaped onto shared
        // condition/value/account-rule vocabularies: every CSV and API strategy rehashed at once.
        const val KEY_BASELINE_PREFIX = "strategySync.i."

        // Must be a printable, XML-safe character: on JVM these values live in java.util.prefs' prefs.xml,
        // where an XML-illegal character (e.g. U+0001) makes the whole node fail to flush, silently
        // losing every setting written that session. '|' cannot appear in Drive file ids/revisions
        // (URL-safe base64) or in the hex content hash.
        const val FIELD_SEP = "|"
        const val BASELINE_FIELDS = 3
    }
}

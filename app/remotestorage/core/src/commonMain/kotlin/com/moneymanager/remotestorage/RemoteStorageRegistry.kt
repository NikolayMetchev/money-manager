package com.moneymanager.remotestorage

/**
 * Holds the remote-storage providers available in this build, so UI can enumerate backends and the
 * sync layer can re-resolve a provider by its persisted [RemoteStorageProvider.id].
 */
class RemoteStorageRegistry(
    private val providers: List<RemoteStorageProvider>,
) {
    fun all(): List<RemoteStorageProvider> = providers

    fun byId(id: String): RemoteStorageProvider? = providers.firstOrNull { it.id == id }
}

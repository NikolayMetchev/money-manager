package com.moneymanager.remotestorage.googledrive

import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.store.FileDataStoreFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.moneymanager.remotestorage.RemoteAuthException
import com.moneymanager.remotestorage.RemoteFile
import com.moneymanager.remotestorage.RemoteStorageException
import com.moneymanager.remotestorage.RemoteStorageProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lighthousegames.logging.logging
import java.io.File
import com.google.api.services.drive.model.File as DriveFile

/** Provider id shared with the DI factory; persisted in the database binding to re-bind on launch. */
const val GOOGLE_DRIVE_PROVIDER_ID = "google-drive"

private val logger = logging()

/**
 * A [RemoteStorageProvider] backed by Google Drive, using the official Drive Java SDK.
 *
 * Authentication is the installed-app OAuth flow: [signIn] opens the system browser and captures the
 * authorization code on a localhost loopback ([LocalServerReceiver]); the resulting refresh token is
 * persisted under [tokensDir] so later launches restore the session without prompting.
 *
 * Least privilege: only the [DriveScopes.DRIVE_FILE] scope is requested, so the app can see and manage
 * **only the files it created** — never the user's other Drive content. Uploaded archives are tagged
 * with an app-property ([APP_PROPERTY_KEY]) so [list] can find them again across devices.
 *
 * @param clientSecretsFile the OAuth client secrets JSON downloaded from Google Cloud Console
 *   (an "OAuth client ID" of type "Desktop app"). See the module README for setup.
 * @param tokensDir directory where the refresh token is cached between runs.
 */
class GoogleDriveProvider(
    private val clientSecretsFile: File,
    private val tokensDir: File,
    override val id: String = GOOGLE_DRIVE_PROVIDER_ID,
    override val displayName: String = "Google Drive",
) : RemoteStorageProvider {
    private val jsonFactory = GsonFactory.getDefaultInstance()
    private val httpTransport by lazy { GoogleNetHttpTransport.newTrustedTransport() }

    private fun authorizationFlow(): GoogleAuthorizationCodeFlow {
        if (!clientSecretsFile.isFile) {
            throw RemoteAuthException(
                "Google OAuth client secrets not found at ${clientSecretsFile.path}. " +
                    "Download an OAuth client ID (Desktop app) from Google Cloud Console — see the README.",
            )
        }
        val secrets = clientSecretsFile.reader().use { GoogleClientSecrets.load(jsonFactory, it) }
        return GoogleAuthorizationCodeFlow
            .Builder(httpTransport, jsonFactory, secrets, listOf(DriveScopes.DRIVE_FILE))
            .setDataStoreFactory(FileDataStoreFactory(tokensDir))
            .setAccessType("offline")
            .build()
    }

    private fun driveOrNull(): Drive? {
        val credential = authorizationFlow().loadCredential(STORED_USER) ?: return null
        // A loaded credential without a refresh token can't be renewed; treat it as not signed in.
        if (credential.refreshToken == null && credential.accessToken == null) return null
        return Drive
            .Builder(httpTransport, jsonFactory, credential)
            .setApplicationName(APPLICATION_NAME)
            .build()
    }

    private fun requireDrive(): Drive = driveOrNull() ?: throw RemoteAuthException("Not signed in to Google Drive")

    override suspend fun isSignedIn(): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { driveOrNull() != null }.getOrDefault(false)
        }

    override suspend fun signIn() {
        withContext(Dispatchers.IO) {
            try {
                val receiver = LocalServerReceiver.Builder().setPort(LOOPBACK_ANY_PORT).build()
                AuthorizationCodeInstalledApp(authorizationFlow(), receiver).authorize(STORED_USER)
            } catch (expected: Exception) {
                throw RemoteAuthException("Google Drive sign-in failed: ${expected.message}", expected)
            }
        }
    }

    override suspend fun signOut() {
        withContext(Dispatchers.IO) {
            runCatching { authorizationFlow().credentialDataStore?.clear() }
                .onFailure { logger.warn(it) { "Failed to clear Google Drive credentials" } }
        }
    }

    override suspend fun list(): List<RemoteFile> =
        withContext(Dispatchers.IO) {
            val result =
                requireDrive()
                    .files()
                    .list()
                    .setQ("appProperties has { key='$APP_PROPERTY_KEY' and value='true' } and trashed = false")
                    .setSpaces("drive")
                    .setFields("files($REMOTE_FILE_FIELDS)")
                    .setPageSize(PAGE_SIZE)
                    .execute()
            result.files.orEmpty().map { it.toRemoteFile() }
        }

    override suspend fun stat(fileId: String): RemoteFile? =
        withContext(Dispatchers.IO) {
            runCatching {
                requireDrive().files().get(fileId).setFields(REMOTE_FILE_FIELDS).execute().toRemoteFile()
            }.getOrNull()
        }

    override suspend fun download(fileId: String): ByteArray =
        withContext(Dispatchers.IO) {
            try {
                requireDrive().files().get(fileId).executeMediaAsInputStream().use { it.readBytes() }
            } catch (expected: Exception) {
                throw RemoteStorageException("Failed to download $fileId from Google Drive: ${expected.message}", expected)
            }
        }

    override suspend fun upload(
        fileId: String?,
        name: String,
        bytes: ByteArray,
    ): RemoteFile =
        withContext(Dispatchers.IO) {
            val drive = requireDrive()
            val content = ByteArrayContent(MIME_TYPE, bytes)
            try {
                val saved =
                    if (fileId == null) {
                        val metadata =
                            DriveFile().apply {
                                this.name = name
                                appProperties = mapOf(APP_PROPERTY_KEY to "true")
                            }
                        drive.files().create(metadata, content).setFields(REMOTE_FILE_FIELDS).execute()
                    } else {
                        // Update in place so the file id (and thus the binding) stays stable across syncs.
                        val metadata = DriveFile().apply { this.name = name }
                        drive.files().update(fileId, metadata, content).setFields(REMOTE_FILE_FIELDS).execute()
                    }
                saved.toRemoteFile()
            } catch (expected: Exception) {
                throw RemoteStorageException("Failed to upload to Google Drive: ${expected.message}", expected)
            }
        }

    override suspend fun delete(fileId: String) {
        withContext(Dispatchers.IO) {
            try {
                requireDrive().files().delete(fileId).execute()
            } catch (expected: Exception) {
                throw RemoteStorageException("Failed to delete $fileId from Google Drive: ${expected.message}", expected)
            }
        }
    }

    private fun DriveFile.toRemoteFile() =
        RemoteFile(
            id = id,
            name = name,
            sizeBytes = getSize(),
            modifiedAtEpochMs = modifiedTime?.value,
        )

    companion object {
        private const val APPLICATION_NAME = "Money Manager"

        /** Single local user slot for the installed-app token store (this is a desktop, single-user app). */
        private const val STORED_USER = "user"

        /** appProperties marker so the app can find the archives it uploaded (drive.file scope). */
        private const val APP_PROPERTY_KEY = "moneymanagerDb"
        private const val MIME_TYPE = "application/octet-stream"
        private const val REMOTE_FILE_FIELDS = "id, name, size, modifiedTime"
        private const val PAGE_SIZE = 100

        /** Tells [LocalServerReceiver] to bind any free loopback port for the OAuth redirect. */
        private const val LOOPBACK_ANY_PORT = -1

        /**
         * Builds a provider from a [config] string persisted in the database binding. The config is the
         * path to the OAuth client secrets JSON; when null/blank a conventional default location under
         * [appDir] is used so users can simply drop their `credentials.json` there.
         */
        fun forConfig(
            config: String?,
            appDir: File,
        ): GoogleDriveProvider {
            val secrets =
                config?.takeIf { it.isNotBlank() }?.let { File(it) }
                    ?: File(appDir, DEFAULT_CLIENT_SECRETS_FILE_NAME)
            return GoogleDriveProvider(secrets, File(appDir, TOKENS_DIR_NAME))
        }

        const val DEFAULT_CLIENT_SECRETS_FILE_NAME = "google-drive-credentials.json"
        const val TOKENS_DIR_NAME = "google-drive-tokens"
    }
}

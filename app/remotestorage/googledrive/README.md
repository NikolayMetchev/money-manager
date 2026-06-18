# Google Drive remote-storage backend

A [`RemoteStorageProvider`](../core) implementation that stores the encrypted, compressed database
archive (`*.mmenc`, produced by [`utils/archive`](../../../utils/archive)) on the user's Google Drive.

The database is never opened "live" from Drive — SQLite needs a local file. A Drive-backed database is a
**local working copy** that is hydrated from Drive on open and pushed back on close (see
[`app/remotestorage/sync`](../sync)). This module only provides the storage transport.

## Least privilege

Only the **`drive.file`** scope is requested, so Money Manager can see and manage **only the files it
created** — never the rest of the user's Drive. Uploaded archives are tagged with an
`appProperties` marker (`moneymanagerDb=true`) so the app can find them again across devices.

## One-time setup: create an OAuth client

The Drive API requires *your own* OAuth client; there is nothing to ship a secret for in an open-source
desktop app, so each user supplies their own (this is the standard installed-app pattern).

1. Open the [Google Cloud Console](https://console.cloud.google.com/) and create (or pick) a project.
2. **APIs & Services → Library** → enable the **Google Drive API**.
3. **APIs & Services → OAuth consent screen** → configure it (External is fine), add your Google
   account under **Test users** while the app is unverified.
4. **APIs & Services → Credentials → Create credentials → OAuth client ID** → application type
   **Desktop app** → **Download JSON**.
5. Save that file as `~/.moneymanager/google-drive-credentials.json` (the default location), or point the
   provider at a custom path via the database binding's provider config.

On first use, **Store database → Google Drive** opens your browser for consent; the resulting refresh
token is cached under `~/.moneymanager/google-drive-tokens/` so later launches restore the session
silently.

## Platform support

| Platform | Status | How |
|----------|--------|-----|
| **JVM (desktop)** | ✅ implemented | Installed-app OAuth via `google-oauth-client-jetty`; browser + localhost loopback (`LocalServerReceiver`), refresh token in `FileDataStoreFactory`. |
| **Android** | ⚠️ follow-up | Use the Credential Manager / Google Sign-In account picker to obtain a `GoogleAccountCredential` with the `drive.file` scope, then reuse the Drive Java SDK. Needs Activity integration (account picker + consent) and an Android OAuth client (with the app's SHA-1), so it lives outside this pure-Kotlin module. Until then, Android falls back to the local/synced-folder backend (a Drive desktop-sync folder still works). |
| **iOS** | ⚠️ planned | The Drive Java SDK is unavailable. The iOS `actual` will call the **Drive REST API via Ktor** (reuse `utils/rest`) with OAuth through the **GoogleSignIn iOS SDK / `ASWebAuthenticationSession`**, behind this same `RemoteStorageProvider` contract. The `commonMain` archive codec (KMP crypto) and the generic interface are exactly what make this drop-in. |

Because every backend speaks the same `RemoteStorageProvider` interface and exchanges opaque
`ByteArray` archives, adding OneDrive / Dropbox / iDrive later is the same shape as this module.

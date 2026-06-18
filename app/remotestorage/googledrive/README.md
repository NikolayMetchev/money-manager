# Google Drive remote-storage backend

A [`RemoteStorageProvider`](../core) implementation that stores the encrypted, compressed database
archive (`*.mmenc`, produced by [`utils/archive`](../../../utils/archive)) on the user's Google Drive.

The database is never opened "live" from Drive — SQLite needs a local file. A Drive-backed database is a
**local working copy** hydrated from Drive on open and pushed back on close (see
[`app/remotestorage/sync`](../sync)). This module only provides the storage transport.

## Design: REST over Ktor, runs on JVM **and** Android

Unlike the Google Java SDK (whose OAuth helpers are JVM-only), this backend talks to the **Drive REST API
v3 over the shared KMP [Ktor](../../../utils/rest) client** and does the OAuth code exchange itself, so the
*entire* provider lives in one `jvmAndroidMain` source set and behaves identically on JVM and Android. The
only platform-specific piece is launching the system browser ([`BrowserLauncher`](src) — `Desktop.browse`
on JVM, an `ACTION_VIEW` intent on Android).

- `GoogleDriveProvider` — Drive REST v3 (`list`/`download`/multipart `upload`/`delete`).
- `GoogleOAuth` — consent URL, `code`→tokens, `refresh_token`→access token.
- `LoopbackRedirectReceiver` — a raw `java.net.ServerSocket` on `127.0.0.1` that catches the OAuth redirect
  (works on JVM and Android; loopback is the only redirect a Desktop OAuth client allows).
- `GoogleDriveAccountStore` — persists the refresh token in `LocalSettings`, keyed by OAuth client id.

## Bring-your-own credentials (no app-owned secrets)

This app ships **no** Google API credentials. Each user creates **their own** OAuth client; the in-app
setup wizard (Settings → Cloud storage → "Store/Open … Google Drive") guides them and never sees an
app-owned secret. Per database: each Drive-backed database stores its own OAuth client in its binding, so
different databases can use different Google accounts.

### One-time setup the wizard walks the user through
1. Open the [Google Cloud Console](https://console.cloud.google.com/) and create or pick a project.
2. **APIs & Services → Library** → enable the **Google Drive API**.
3. **APIs & Services → OAuth consent screen** → configure it (External is fine) and add the Google account
   under **Test users** (the `drive.file` scope is "sensitive", so an unverified app must allowlist testers;
   users otherwise see a bypassable "unverified app" warning).
4. **Credentials → Create credentials → OAuth client ID** → application type **Desktop app** →
   **Download JSON**.
5. Paste that `credentials.json` into the wizard and **Sign in with Google**. The browser opens for consent;
   the resulting refresh token is cached in `LocalSettings` so later launches refresh silently.

## Least privilege

Only the **`drive.file`** scope is requested, so Money Manager can see and manage **only the files it
created** — never the rest of the user's Drive. Uploaded archives are tagged with an `appProperties`
marker (`moneymanagerDb=true`) so the app can find them again across devices, and updates happen in place
so the remote file id stays stable across syncs.

## iOS (planned)

The same REST-over-Ktor provider is a near drop-in for iOS; only `BrowserLauncher` and the loopback
receiver need Apple actuals (or `ASWebAuthenticationSession`). The `commonMain` archive codec (KMP crypto)
and the generic `RemoteStorageProvider` interface are what make this — and future OneDrive/Dropbox/iDrive
backends — the same shape.

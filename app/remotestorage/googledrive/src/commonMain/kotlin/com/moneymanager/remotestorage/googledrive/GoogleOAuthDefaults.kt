package com.moneymanager.remotestorage.googledrive

/** The desktop Google OAuth client the build ships, if it was built with one (see `generateGoogleOAuthDefaults`). */
object GoogleOAuthDefaults {
    val clientId: String get() = GeneratedGoogleOAuth.CLIENT_ID
    val clientSecret: String get() = GeneratedGoogleOAuth.CLIENT_SECRET
    val isConfigured: Boolean get() = clientId.isNotEmpty() && clientSecret.isNotEmpty()
}

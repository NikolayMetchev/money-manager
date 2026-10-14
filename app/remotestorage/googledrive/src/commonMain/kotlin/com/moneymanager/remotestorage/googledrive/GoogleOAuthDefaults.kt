package com.moneymanager.remotestorage.googledrive

/** The desktop Google OAuth client the build ships, if it was built with one (see `generateGoogleOAuthDefaults`). */
object GoogleOAuthDefaults {
    val clientId: String get() = GeneratedGoogleOAuth.CLIENT_ID
    val clientSecret: String get() = GeneratedGoogleOAuth.CLIENT_SECRET
    val isConfigured: Boolean get() = clientId.isNotEmpty() && clientSecret.isNotEmpty()

    /**
     * The shipped client serialized as provider config, or null when this build wasn't given the secret, in
     * which case sign-in surfaces a "not configured" error.
     */
    fun providerConfig(): String? = if (isConfigured) GoogleDriveCredentials(clientId, clientSecret).toConfig() else null
}

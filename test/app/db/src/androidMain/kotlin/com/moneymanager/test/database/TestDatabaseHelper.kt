package com.moneymanager.test.database

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.moneymanager.di.AppComponentParams
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.remotestorage.googledrive.GoogleAccessTokenSource
import com.moneymanager.remotestorage.googledrive.buildBearerDriveClient
import io.ktor.client.HttpClient

actual fun createTestDatabaseLocation(): DbLocation {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val dbName = "test-${System.currentTimeMillis()}.db"
    // Clean up first if it exists
    context.deleteDatabase(dbName)
    return DbLocation(dbName)
}

actual fun deleteTestDatabase(location: DbLocation) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    context.deleteDatabase(location.name)
}

actual fun createTestAppComponentParams(): AppComponentParams =
    AppComponentParams(ApplicationProvider.getApplicationContext(), NoopGoogleAccessTokenSource)

/** Tests never exercise Google Drive sign-in; this satisfies the Android [AppComponentParams] field. */
private object NoopGoogleAccessTokenSource : GoogleAccessTokenSource {
    override val httpClient: HttpClient by lazy { buildBearerDriveClient(loadToken = { null }, refreshToken = { null }) }

    override suspend fun isSignedIn(): Boolean = false

    override suspend fun signIn(): Unit = throw UnsupportedOperationException("Google Drive sign-in is not used in tests")

    override suspend fun signOut() = Unit

    override suspend fun accessToken(): String? = null

    override suspend fun accessTokenExpiresAtEpochMs(): Long? = null
}

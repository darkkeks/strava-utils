package stravahooks.strava

import stravahooks.config.StravaHooksConfig
import stravahooks.storage.DataStore
import stravahooks.storage.StoredUser
import stravahooks.storage.StravaAccount
import kotlinx.coroutines.runBlocking
import java.time.Instant

class TokenRefresher(
    private val config: StravaHooksConfig,
    private val dataStore: DataStore,
    private val stravaApi: StravaApi
) {
    fun refreshAccountIfNeeded(user: StoredUser): StravaAccount? {
        val account = user.strava ?: return null
        val now = Instant.now().epochSecond
        if (account.expiresAt > now + 60) {
            return account
        }
        val clientId = config.stravaClientId
        val clientSecret = config.stravaClientSecret
        if (clientId.isNullOrBlank() || clientSecret.isNullOrBlank()) {
            return account
        }
        val refreshed = runBlocking { stravaApi.refreshToken(clientId, clientSecret, account.refreshToken) }
        val validToken = refreshed.success &&
            !refreshed.accessToken.isNullOrBlank() &&
            !refreshed.refreshToken.isNullOrBlank() &&
            refreshed.expiresAt != null
        if (!validToken) {
            return account
        }
        val updated = account.copy(
            accessToken = refreshed.accessToken,
            refreshToken = refreshed.refreshToken,
            expiresAt = refreshed.expiresAt
        )
        dataStore.upsertUser(user.telegramUserId) { existing ->
            existing.copy(strava = updated)
        }
        return updated
    }
}

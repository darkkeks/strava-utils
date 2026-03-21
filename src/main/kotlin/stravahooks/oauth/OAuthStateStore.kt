package stravahooks.oauth

import stravahooks.storage.DataStore
import stravahooks.storage.OAuthStateEntry
import java.security.SecureRandom
import java.util.Base64

class OAuthStateStore(
    private val dataStore: DataStore,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val random = SecureRandom()

    fun issue(telegramUserId: Long): String {
        val bytes = ByteArray(18)
        random.nextBytes(bytes)
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        dataStore.putOAuthState(
            OAuthStateEntry(
                nonce = nonce,
                telegramUserId = telegramUserId,
                issuedAt = nowMillis()
            )
        )
        return nonce
    }

    fun consume(state: String): Long? {
        return dataStore.consumeOAuthState(state, STATE_TTL_MILLIS, nowMillis())
    }

    companion object {
        private const val STATE_TTL_MILLIS = 10 * 60 * 1000L
    }
}

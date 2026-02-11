package stravahooks.oauth

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

object OAuthStateStore {
    private val random = SecureRandom()
    private val issued = ConcurrentHashMap<String, Long?>()

    fun issue(telegramUserId: Long?): String {
        val bytes = ByteArray(18)
        random.nextBytes(bytes)
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        issued[nonce] = telegramUserId
        return nonce
    }

    fun consume(state: String): Long? {
        return issued.remove(state)
    }
}

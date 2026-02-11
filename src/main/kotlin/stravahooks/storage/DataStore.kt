package stravahooks.storage

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class DataStore(private val path: Path) {
    private val mapper = jacksonObjectMapper()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .enable(SerializationFeature.INDENT_OUTPUT)

    @Synchronized
    fun load(): StoredState {
        if (!path.exists()) {
            return StoredState()
        }
        return mapper.readValue(path.toFile())
    }

    @Synchronized
    fun save(state: StoredState) {
        val parent = path.parent
        if (parent != null && !parent.exists()) {
            parent.createDirectories()
        }
        path.writeText(mapper.writeValueAsString(state))
    }

    @Synchronized
    fun upsertUser(telegramUserId: Long, update: (StoredUser) -> StoredUser): StoredUser {
        val state = load()
        val existing = state.users.firstOrNull { it.telegramUserId == telegramUserId }
        val updated = update(existing ?: StoredUser(telegramUserId = telegramUserId))
        val newUsers = state.users.filterNot { it.telegramUserId == telegramUserId } + updated
        save(state.copy(users = newUsers))
        return updated
    }
}

data class StoredState(
    val users: List<StoredUser> = emptyList()
)

data class StoredUser(
    val telegramUserId: Long,
    val strava: StravaAccount? = null
)

data class StravaAccount(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val scope: String,
    val athleteId: Long? = null
)

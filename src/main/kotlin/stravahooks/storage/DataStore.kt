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

    @Synchronized
    fun getUser(telegramUserId: Long): StoredUser? {
        val state = load()
        return state.users.firstOrNull { it.telegramUserId == telegramUserId }
    }

    @Synchronized
    fun putOAuthState(entry: OAuthStateEntry) {
        val state = load()
        val newEntries = state.oauthStates.filterNot { it.nonce == entry.nonce } + entry
        save(state.copy(oauthStates = newEntries))
    }

    @Synchronized
    fun consumeOAuthState(nonce: String, ttlMillis: Long, nowMillis: Long): Long? {
        val state = load()
        val (valid, expired) = state.oauthStates.partition { nowMillis - it.issuedAt <= ttlMillis }
        val match = valid.firstOrNull { it.nonce == nonce }
        val remaining = valid.filterNot { it.nonce == nonce }
        if (expired.isNotEmpty() || match != null) {
            save(state.copy(oauthStates = remaining))
        }
        return match?.telegramUserId
    }

    @Synchronized
    fun appendApplyLog(entry: ApplyLogEntry) {
        val state = load()
        val capped = (state.applyLogs + entry).takeLast(MAX_LOG_ENTRIES)
        save(state.copy(applyLogs = capped))
    }

    @Synchronized
    fun getApplyLogs(telegramUserId: Long, limit: Int): List<ApplyLogEntry> {
        val state = load()
        return state.applyLogs
            .asReversed()
            .filter { it.telegramUserId == telegramUserId }
            .take(limit)
    }

    companion object {
        private const val MAX_LOG_ENTRIES = 200
    }
}

data class StoredState(
    val users: List<StoredUser> = emptyList(),
    val oauthStates: List<OAuthStateEntry> = emptyList(),
    val applyLogs: List<ApplyLogEntry> = emptyList()
)

data class StoredUser(
    val telegramUserId: Long,
    val strava: StravaAccount? = null,
    val actions: List<ActionDefinition> = emptyList(),
    val pendingActionEdit: PendingActionEdit? = null,
    val pendingActionCreate: PendingActionCreate? = null,
    val pendingApply: PendingApply? = null,
    val lastPolledAt: Long? = null
)

data class OAuthStateEntry(
    val nonce: String,
    val telegramUserId: Long?,
    val issuedAt: Long
)

data class StravaAccount(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val scope: String,
    val athleteId: Long? = null,
    val athleteName: String? = null
)

data class ActionDefinition(
    val id: String,
    val name: String,
    val description: String? = null,
    val code: String,
    val enabled: Boolean = false,
    val order: Int,
    val createdAt: Long,
    val updatedAt: Long
)

data class PendingActionEdit(
    val actionId: String,
    val startedAt: Long
)

data class PendingActionCreate(
    val startedAt: Long,
    val stage: String,
    val name: String? = null
)

data class PendingApply(
    val activityId: Long,
    val actionIds: List<String>,
    val update: ActivityUpdate,
    val summary: String,
    val createdAt: Long
)

data class ActivityUpdate(
    val fields: Set<String> = emptySet(),
    val name: String? = null,
    val description: String? = null,
    val commute: Boolean? = null,
    val trainer: Boolean? = null,
    val mute: Boolean? = null,
    val gearId: String? = null
)

data class ApplyLogEntry(
    val timestamp: Long,
    val telegramUserId: Long,
    val activityId: Long,
    val actionIds: List<String>,
    val actionNames: List<String>,
    val mode: String,
    val summary: String,
    val changes: Map<String, ChangePair>,
    val logs: List<String>,
    val result: String,
    val error: String? = null
)

data class ChangePair(
    val before: String?,
    val after: String?
)

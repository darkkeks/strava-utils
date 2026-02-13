package stravahooks.polling

import stravahooks.actions.ActionEngine
import stravahooks.config.StravaHooksConfig
import stravahooks.storage.DataStore
import stravahooks.storage.ApplyLogEntry
import stravahooks.strava.StravaClient
import stravahooks.strava.StravaSummaryActivity
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.concurrent.thread
import kotlin.math.max

class ActivityPoller(
    private val config: StravaHooksConfig,
    private val dataStore: DataStore
) {
    private val logger = LoggerFactory.getLogger(ActivityPoller::class.java)
    private val actionEngine = ActionEngine()
    private val stravaClient = StravaClient()

    fun start() {
        if (!config.polling) {
            return
        }
        thread(name = "activity-poller", isDaemon = true) {
            val intervalSeconds = config.pollingIntervalSeconds ?: 300
            while (true) {
                try {
                    pollOnce()
                } catch (e: Exception) {
                    logger.warn("Polling failed: ${e.message}")
                }
                Thread.sleep(intervalSeconds * 1000)
            }
        }
    }

    private fun pollOnce() {
        val state = dataStore.load()
        val now = Instant.now().epochSecond
        val lookbackSeconds = config.pollingLookbackSeconds ?: 3600

        state.users.forEach { user ->
            val account = refreshAccountIfNeeded(user) ?: return@forEach
            val enabledActions = user.actions.filter { it.enabled }.sortedBy { it.order }
            if (enabledActions.isEmpty()) {
                return@forEach
            }
            val after = user.lastPolledAt ?: (now - lookbackSeconds)
            val scopeSet = account.scope.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            if (!scopeSet.contains("activity:write")) {
                return@forEach
            }

            val recent = stravaClient.fetchActivitiesSince(account.accessToken, after, 30)
            if (recent.isEmpty()) {
                return@forEach
            }

            val ordered = recent
                .mapNotNull { summary -> toActivityMeta(summary, now) }
                .sortedBy { it.startEpoch }

            var maxSeen = after
            ordered.forEach { meta ->
                maxSeen = max(maxSeen, meta.startEpoch)
                val activity = stravaClient.fetchActivity(account.accessToken, meta.id) ?: return@forEach
                val normalized = actionEngine.normalizeActivity(activity)
                val before = actionEngine.snapshotWritable(normalized)
                val run = actionEngine.runActions(enabledActions, normalized)
                if (run.errors.isNotEmpty()) {
                    logger.warn("Action errors for ${meta.id}: ${run.errors.joinToString("; ")}")
                    dataStore.appendApplyLog(
                        ApplyLogEntry(
                            timestamp = Instant.now().epochSecond,
                            telegramUserId = user.telegramUserId,
                            activityId = meta.id,
                            actionIds = enabledActions.map { it.id },
                            actionNames = enabledActions.map { it.name },
                            mode = "poll",
                            summary = "Action error",
                            changes = emptyMap(),
                            logs = run.logs,
                            result = "error",
                            error = run.errors.joinToString("; ")
                        )
                    )
                    return@forEach
                }
                val changes = actionEngine.diffWritable(before, normalized)
                if (changes.isEmpty()) {
                    dataStore.appendApplyLog(
                        ApplyLogEntry(
                            timestamp = Instant.now().epochSecond,
                            telegramUserId = user.telegramUserId,
                            activityId = meta.id,
                            actionIds = enabledActions.map { it.id },
                            actionNames = enabledActions.map { it.name },
                            mode = "poll",
                            summary = "No changes",
                            changes = emptyMap(),
                            logs = run.logs,
                            result = "no_changes",
                            error = null
                        )
                    )
                    return@forEach
                }
                val update = actionEngine.buildUpdate(changes)
                val body = actionEngine.buildUpdateBody(update)
                val result = stravaClient.updateActivity(account.accessToken, meta.id, body)
                if (!result.success) {
                    val detail = result.status?.let { " ($it)" } ?: ""
                    logger.warn("Update failed$detail for ${meta.id}: ${result.body ?: result.error}")
                }
                dataStore.appendApplyLog(
                    ApplyLogEntry(
                        timestamp = Instant.now().epochSecond,
                        telegramUserId = user.telegramUserId,
                        activityId = meta.id,
                        actionIds = enabledActions.map { it.id },
                        actionNames = enabledActions.map { it.name },
                        mode = "poll",
                        summary = buildChangeSummary(changes),
                        changes = actionEngine.toChangePairs(changes),
                        logs = run.logs,
                        result = if (result.success) "applied" else "failed",
                        error = result.error ?: result.body
                    )
                )
            }

            if (maxSeen > after) {
                dataStore.upsertUser(user.telegramUserId) { existing ->
                    existing.copy(lastPolledAt = maxSeen)
                }
            }
        }
    }

    private fun refreshAccountIfNeeded(user: stravahooks.storage.StoredUser): stravahooks.storage.StravaAccount? {
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
        val refreshed = stravaClient.refreshToken(clientId, clientSecret, account.refreshToken)
        if (!refreshed.success || refreshed.accessToken.isNullOrBlank() || refreshed.refreshToken.isNullOrBlank() || refreshed.expiresAt == null) {
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

    private fun buildChangeSummary(changes: Map<String, Pair<Any?, Any?>>): String {
        return changes.entries.joinToString("\n") { (key, value) ->
            val before = value.first?.toString() ?: "null"
            val after = value.second?.toString() ?: "null"
            "$key: $before -> $after"
        }
    }

    private fun toActivityMeta(summary: StravaSummaryActivity, fallbackEpoch: Long): ActivityMeta? {
        val id = summary.id ?: return null
        val startEpoch = parseStartDate(summary.startDate) ?: fallbackEpoch
        return ActivityMeta(id, startEpoch)
    }

    private fun parseStartDate(value: String?): Long? {
        if (value.isNullOrBlank()) {
            return null
        }
        return try {
            Instant.parse(value).epochSecond
        } catch (_: Exception) {
            null
        }
    }

    private data class ActivityMeta(
        val id: Long,
        val startEpoch: Long
    )
}

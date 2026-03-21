package stravahooks.polling

import stravahooks.actions.ActionEngine
import stravahooks.actions.activityUrl
import stravahooks.actions.buildChangeSummary
import stravahooks.config.StravaHooksConfig
import stravahooks.storage.DataStore
import stravahooks.storage.ApplyLogEntry
import stravahooks.storage.ApplyMode
import stravahooks.storage.ApplyResult
import stravahooks.strava.StravaApi
import stravahooks.strava.StravaClient
import stravahooks.strava.StravaSummaryActivity
import stravahooks.strava.TokenRefresher
import stravahooks.telegram.NotificationSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.math.max

class ActivityPoller(
    private val config: StravaHooksConfig,
    private val dataStore: DataStore,
    private val stravaApi: StravaApi = StravaClient(),
    private val actionEngine: ActionEngine = ActionEngine(),
    private val notificationSender: NotificationSender? = null,
    private val tokenRefresher: TokenRefresher = TokenRefresher(config, dataStore, stravaApi)
) {
    private val logger = LoggerFactory.getLogger(ActivityPoller::class.java)

    fun start(): Job {
        if (!config.polling) return Job()
        val intervalMs = (config.pollingIntervalSeconds ?: DEFAULT_POLL_INTERVAL_SECONDS).toLong() * MILLIS_PER_SECOND
        return CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    pollOnce()
                } catch (e: Exception) {
                    logger.warn("Polling failed: ${e.message}")
                }
                delay(intervalMs)
            }
        }
    }

    internal suspend fun pollOnce() {
        val state = dataStore.load()
        val now = Instant.now().epochSecond
        val lookbackSeconds = config.pollingLookbackSeconds ?: 3600
        val maxEdits = config.maxPollEditsPerCycle ?: 10
        var editsThisCycle = 0

        state.users.forEach { user ->
            val account = tokenRefresher.refreshAccountIfNeeded(user) ?: return@forEach
            val enabledActions = user.actions.filter { it.enabled }.sortedBy { it.order }
            if (enabledActions.isEmpty()) {
                return@forEach
            }
            val after = user.lastPolledAt ?: (now - lookbackSeconds)
            val scopeSet = account.scope.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            if (!scopeSet.contains("activity:write")) {
                return@forEach
            }

            val recent = stravaApi.fetchActivitiesSince(account.accessToken, after, 30)
            if (recent.isEmpty()) {
                return@forEach
            }

            val ordered = recent
                .mapNotNull { summary -> toActivityMeta(summary, now) }
                .sortedBy { it.startEpoch }

            var maxSeen = after
            for (meta in ordered) {
                if (editsThisCycle >= maxEdits) {
                    logger.info("Reached per-cycle edit cap ($maxEdits), stopping")
                    break
                }
                maxSeen = max(maxSeen, meta.startEpoch)
                val activity = stravaApi.fetchActivity(account.accessToken, meta.id) ?: continue
                val normalized = actionEngine.normalizeActivity(activity)
                val mutableNormalized = normalized.toMutableMap()
                val before = actionEngine.snapshotWritable(mutableNormalized)
                val run = actionEngine.runActions(enabledActions, mutableNormalized)
                if (run.errors.isNotEmpty()) {
                    logger.warn("Action errors for ${meta.id}: ${run.errors.joinToString("; ")}")
                    dataStore.appendApplyLog(
                        ApplyLogEntry(
                            timestamp = Instant.now().epochSecond,
                            telegramUserId = user.telegramUserId,
                            activityId = meta.id,
                            actionIds = enabledActions.map { it.id },
                            actionNames = enabledActions.map { it.name },
                            mode = ApplyMode.POLL,
                            summary = "Action error",
                            changes = emptyMap(),
                            logs = run.logs,
                            result = ApplyResult.ERROR,
                            error = run.errors.joinToString("; ")
                        )
                    )
                    notificationSender?.sendNotification(
                        user.telegramUserId,
                        "Action error on activity ${activityUrl(meta.id)}:\n${run.errors.joinToString("\n")}"
                    )
                    continue
                }
                val validation = actionEngine.validateChanges(normalized, mutableNormalized)
                if (!validation.isValid) {
                    logger.warn("Validation errors for ${meta.id}: ${validation.errorMessage()}")
                    dataStore.appendApplyLog(
                        ApplyLogEntry(
                            timestamp = Instant.now().epochSecond,
                            telegramUserId = user.telegramUserId,
                            activityId = meta.id,
                            actionIds = enabledActions.map { it.id },
                            actionNames = enabledActions.map { it.name },
                            mode = ApplyMode.POLL,
                            summary = "Validation error",
                            changes = emptyMap(),
                            logs = run.logs,
                            result = ApplyResult.ERROR,
                            error = validation.errorMessage()
                        )
                    )
                    continue
                }
                val changes = actionEngine.diffWritable(before, mutableNormalized)
                if (changes.isEmpty()) {
                    dataStore.appendApplyLog(
                        ApplyLogEntry(
                            timestamp = Instant.now().epochSecond,
                            telegramUserId = user.telegramUserId,
                            activityId = meta.id,
                            actionIds = enabledActions.map { it.id },
                            actionNames = enabledActions.map { it.name },
                            mode = ApplyMode.POLL,
                            summary = "No changes",
                            changes = emptyMap(),
                            logs = run.logs,
                            result = ApplyResult.NO_CHANGES,
                            error = null
                        )
                    )
                    continue
                }
                val update = actionEngine.buildUpdate(changes)
                val body = actionEngine.buildUpdateBody(update)
                val result = stravaApi.updateActivity(account.accessToken, meta.id, body)
                if (!result.success) {
                    val detail = result.status?.let { " ($it)" } ?: ""
                    logger.warn("Update failed$detail for ${meta.id}: ${result.body ?: result.error}")
                }
                if (result.success) {
                    editsThisCycle++
                }
                dataStore.appendApplyLog(
                    ApplyLogEntry(
                        timestamp = Instant.now().epochSecond,
                        telegramUserId = user.telegramUserId,
                        activityId = meta.id,
                        actionIds = enabledActions.map { it.id },
                        actionNames = enabledActions.map { it.name },
                        mode = ApplyMode.POLL,
                        summary = buildChangeSummary(changes),
                        changes = actionEngine.toChangePairs(changes),
                        logs = run.logs,
                        result = if (result.success) ApplyResult.APPLIED else ApplyResult.FAILED,
                        error = result.error ?: result.body
                    )
                )
                if (result.success) {
                    notificationSender?.sendNotification(
                        user.telegramUserId,
                        "Applied changes to activity ${activityUrl(meta.id)}:\n${buildChangeSummary(changes)}"
                    )
                }
            }

            if (maxSeen > after) {
                dataStore.upsertUser(user.telegramUserId) { existing ->
                    existing.copy(lastPolledAt = maxSeen)
                }
            }
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

    companion object {
        private const val DEFAULT_POLL_INTERVAL_SECONDS = 300
        private const val MILLIS_PER_SECOND = 1000L
    }
}

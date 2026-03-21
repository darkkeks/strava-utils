package stravahooks.polling

import stravahooks.actions.ActionEngine
import stravahooks.config.StravaHooksConfig
import stravahooks.storage.*
import stravahooks.storage.ApplyResult
import stravahooks.strava.*
import stravahooks.telegram.FakeNotificationSender
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActivityPollerTest {
    private val tmpDir = Files.createTempDirectory("poller-test")
    private val dataStore = DataStore(tmpDir.resolve("data.json"))
    private val fakeApi = FakeStravaApi()
    private val actionEngine = ActionEngine()

    private fun makeConfig(
        maxEdits: Int? = null,
        pollingLookbackSeconds: Long? = 3600
    ) = StravaHooksConfig(
        telegramBotToken = "test-token",
        polling = true,
        pollingLookbackSeconds = pollingLookbackSeconds,
        maxPollEditsPerCycle = maxEdits
    )

    private val fakeNotifier = FakeNotificationSender()

    private fun makePoller(config: StravaHooksConfig = makeConfig(), notifier: FakeNotificationSender? = fakeNotifier) =
        ActivityPoller(config, dataStore, fakeApi, actionEngine, notifier)

    private fun seedUser(
        telegramUserId: Long = 1L,
        actions: List<ActionDefinition> = emptyList(),
        scope: String = "activity:read,activity:write",
        lastPolledAt: Long? = null,
        expiresAt: Long = Long.MAX_VALUE
    ) {
        dataStore.upsertUser(telegramUserId) {
            it.copy(
                strava = StravaAccount(
                    accessToken = "token",
                    refreshToken = "refresh",
                    expiresAt = expiresAt,
                    scope = scope
                ),
                actions = actions,
                lastPolledAt = lastPolledAt
            )
        }
    }

    private fun makeAction(
        id: String = "a1",
        name: String = "Test Action",
        code: String = "activity.name = activity.name + ' edited';",
        enabled: Boolean = true,
        order: Int = 0
    ) = ActionDefinition(
        id = id,
        name = name,
        code = code,
        enabled = enabled,
        order = order,
        createdAt = 1000L,
        updatedAt = 1000L
    )

    private fun seedActivity(id: Long, name: String = "Morning Ride", type: String = "Ride") {
        fakeApi.activities[id] = StravaActivity(
            id = id,
            name = name,
            type = type,
            distance = 10000.0,
            movingTime = 1800,
            elapsedTime = 2000,
            averageSpeed = 5.5
        )
        fakeApi.summaries.add(StravaSummaryActivity(
            id = id,
            name = name,
            startDate = "2025-01-01T10:00:00Z"
        ))
    }

    @Test
    fun `no users does nothing`() {
        val poller = makePoller()
        runBlocking { poller.pollOnce() }
        assertTrue(fakeApi.updateCalls.isEmpty())
    }

    @Test
    fun `user with no enabled actions does nothing`() {
        seedUser(actions = listOf(makeAction(enabled = false)))
        seedActivity(100L)
        val poller = makePoller()
        runBlocking { poller.pollOnce() }
        assertTrue(fakeApi.updateCalls.isEmpty())
    }

    @Test
    fun `user without activity write scope is skipped`() {
        seedUser(actions = listOf(makeAction()), scope = "activity:read")
        seedActivity(100L)
        val poller = makePoller()
        runBlocking { poller.pollOnce() }
        assertTrue(fakeApi.updateCalls.isEmpty())
    }

    @Test
    fun `processes activity and applies update`() {
        seedUser(actions = listOf(makeAction()))
        seedActivity(100L)
        val poller = makePoller()
        runBlocking { poller.pollOnce() }

        assertEquals(1, fakeApi.updateCalls.size)
        assertEquals(100L, fakeApi.updateCalls[0].activityId)
        val body = fakeApi.updateCalls[0].body
        assertEquals("Morning Ride edited", body["name"])

        val logs = dataStore.getApplyLogs(1L, 10)
        assertEquals(1, logs.size)
        assertEquals(ApplyResult.APPLIED, logs[0].result)
    }

    @Test
    fun `no changes results in no update call`() {
        // Action that doesn't change anything
        seedUser(actions = listOf(makeAction(code = "// no-op")))
        seedActivity(100L)
        val poller = makePoller()
        runBlocking { poller.pollOnce() }

        assertTrue(fakeApi.updateCalls.isEmpty())
        val logs = dataStore.getApplyLogs(1L, 10)
        assertEquals(1, logs.size)
        assertEquals(ApplyResult.NO_CHANGES, logs[0].result)
    }

    @Test
    fun `action error is logged without update`() {
        seedUser(actions = listOf(makeAction(code = "null.toString();")))
        seedActivity(100L)
        val poller = makePoller()
        runBlocking { poller.pollOnce() }

        assertTrue(fakeApi.updateCalls.isEmpty())
        val logs = dataStore.getApplyLogs(1L, 10)
        assertEquals(1, logs.size)
        assertEquals(ApplyResult.ERROR, logs[0].result)
    }

    @Test
    fun `edit cap limits number of updates`() {
        seedUser(actions = listOf(makeAction()))
        for (i in 1L..10L) {
            seedActivity(i, name = "Ride $i")
        }
        val config = makeConfig(maxEdits = 3)
        val poller = makePoller(config)
        runBlocking { poller.pollOnce() }

        assertEquals(3, fakeApi.updateCalls.size)
    }

    @Test
    fun `lastPolledAt is updated after processing`() {
        seedUser(lastPolledAt = 0L, actions = listOf(makeAction()))
        seedActivity(100L)
        val poller = makePoller()
        runBlocking { poller.pollOnce() }

        val user = dataStore.getUser(1L)!!
        assertTrue(user.lastPolledAt!! > 0)
    }

    @Test
    fun `validation error for readonly field write is logged`() {
        // Script that writes to a readonly field
        seedUser(actions = listOf(makeAction(code = "activity.type = 'Walk';")))
        seedActivity(100L)
        val poller = makePoller()
        runBlocking { poller.pollOnce() }

        assertTrue(fakeApi.updateCalls.isEmpty())
        val logs = dataStore.getApplyLogs(1L, 10)
        assertEquals(1, logs.size)
        assertEquals(ApplyResult.ERROR, logs[0].result)
        assertTrue(logs[0].error!!.contains("type"))
    }

    // --- Notification tests ---

    @Test
    fun `successful apply sends notification`() {
        seedUser(actions = listOf(makeAction()))
        seedActivity(100L)
        val poller = makePoller()
        runBlocking { poller.pollOnce() }

        assertEquals(1, fakeNotifier.notifications.size)
        assertTrue(fakeNotifier.notifications[0].text.contains("Applied changes"))
        assertTrue(fakeNotifier.notifications[0].text.contains("100"))
        assertEquals(1L, fakeNotifier.notifications[0].telegramUserId)
    }

    @Test
    fun `action error sends notification`() {
        seedUser(actions = listOf(makeAction(code = "null.toString();")))
        seedActivity(100L)
        val poller = makePoller()
        runBlocking { poller.pollOnce() }

        assertEquals(1, fakeNotifier.notifications.size)
        assertTrue(fakeNotifier.notifications[0].text.contains("Action error"))
    }

    @Test
    fun `no changes sends no notification`() {
        seedUser(actions = listOf(makeAction(code = "// no-op")))
        seedActivity(100L)
        val poller = makePoller()
        runBlocking { poller.pollOnce() }

        assertTrue(fakeNotifier.notifications.isEmpty())
    }

    @Test
    fun `null notification sender does not crash`() {
        seedUser(actions = listOf(makeAction()))
        seedActivity(100L)
        val poller = makePoller(notifier = null)
        runBlocking { poller.pollOnce() }

        assertEquals(1, fakeApi.updateCalls.size)
    }
}

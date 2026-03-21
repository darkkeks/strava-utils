package stravahooks.telegram

import stravahooks.actions.ActionEngine
import stravahooks.config.StravaHooksConfig
import stravahooks.storage.*
import stravahooks.storage.ApplyResult
import stravahooks.strava.*
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotActionsTest {
    private val tmpDir = Files.createTempDirectory("botactions-test")
    private val dataStore = DataStore(tmpDir.resolve("data.json"))
    private val fakeApi = FakeStravaApi()
    private val actionEngine = ActionEngine()
    private val config = StravaHooksConfig(
        telegramBotToken = "test-token",
        stravaClientId = "cid",
        stravaClientSecret = "csecret"
    )
    private val botActions = BotActions(config, dataStore, actionEngine, fakeApi)

    private fun seedUser(
        telegramUserId: Long = 1L,
        actions: List<ActionDefinition> = emptyList(),
        scope: String = "activity:read,activity:write",
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
                actions = actions
            )
        }
    }

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
    }

    // --- Action CRUD ---

    @Test
    fun `createAction adds action to DataStore with correct defaults`() {
        seedUser()
        val action = botActions.createAction(1L, "My Action")

        assertEquals("My Action", action.name)
        assertFalse(action.enabled)
        assertEquals(1, action.order)

        val user = dataStore.getUser(1L)!!
        assertEquals(1, user.actions.size)
        assertEquals("My Action", user.actions[0].name)
    }

    @Test
    fun `createAction auto-assigns incrementing order`() {
        seedUser()
        botActions.createAction(1L, "First")
        botActions.createAction(1L, "Second")

        val user = dataStore.getUser(1L)!!
        assertEquals(2, user.actions.size)
        assertEquals(1, user.actions[0].order)
        assertEquals(2, user.actions[1].order)
    }

    @Test
    fun `updateActionCode with valid code updates DataStore`() {
        seedUser()
        val action = botActions.createAction(1L, "Test")
        val updated = botActions.updateActionCode(1L, action.id, "activity.name = 'new';")

        assertTrue(updated)
        val stored = botActions.getAction(1L, action.id)!!
        assertEquals("activity.name = 'new';", stored.code)
    }

    @Test
    fun `updateActionCode returns false for nonexistent action`() {
        seedUser()
        val updated = botActions.updateActionCode(1L, "nonexistent", "code")
        assertFalse(updated)
    }

    @Test
    fun `toggleAction flips enabled state`() {
        seedUser()
        val action = botActions.createAction(1L, "Test")
        assertFalse(botActions.getAction(1L, action.id)!!.enabled)

        botActions.toggleAction(1L, action.id)
        assertTrue(botActions.getAction(1L, action.id)!!.enabled)

        botActions.toggleAction(1L, action.id)
        assertFalse(botActions.getAction(1L, action.id)!!.enabled)
    }

    @Test
    fun `deleteAction removes from DataStore and clears related pending state`() {
        seedUser()
        val action = botActions.createAction(1L, "To Delete")
        botActions.beginActionEdit(1L, action.id)

        val deletedName = botActions.deleteAction(1L, action.id)

        assertEquals("To Delete", deletedName)
        assertNull(botActions.getAction(1L, action.id))
        val user = dataStore.getUser(1L)!!
        assertNull(user.pendingActionEdit)
    }

    @Test
    fun `deleteAction returns null for nonexistent action`() {
        seedUser()
        assertNull(botActions.deleteAction(1L, "nonexistent"))
    }

    // --- Preview / Apply ---

    @Test
    fun `previewApplySingle returns Changes with correct diff`() {
        seedUser()
        val action = botActions.createAction(1L, "Rename")
        botActions.updateActionCode(1L, action.id, "activity.name = activity.name + ' edited';")
        seedActivity(100L)

        val result = botActions.previewApplySingle(1L, "100", action.id)

        assertTrue(result is BotActions.PreviewResult.Changes)
        val changes = result as BotActions.PreviewResult.Changes
        assertEquals(100L, changes.activityId)
        assertTrue(changes.summary.contains("name"))
        assertTrue(changes.summary.contains("Morning Ride edited"))
    }

    @Test
    fun `previewApplySingle with readonly write returns ValidationError`() {
        seedUser()
        val action = botActions.createAction(1L, "Bad")
        botActions.updateActionCode(1L, action.id, "activity.type = 'Walk';")
        seedActivity(100L)

        val result = botActions.previewApplySingle(1L, "100", action.id)

        assertTrue(result is BotActions.PreviewResult.ValidationError)
    }

    @Test
    fun `previewApply runs all enabled actions in order`() {
        seedUser()
        val a1 = botActions.createAction(1L, "First")
        botActions.updateActionCode(1L, a1.id, "activity.name = activity.name + ' [1]';")
        botActions.toggleAction(1L, a1.id)

        val a2 = botActions.createAction(1L, "Second")
        botActions.updateActionCode(1L, a2.id, "activity.name = activity.name + ' [2]';")
        botActions.toggleAction(1L, a2.id)

        seedActivity(100L)

        val result = botActions.previewApply(1L, "100")

        assertTrue(result is BotActions.PreviewResult.Changes)
        val changes = result as BotActions.PreviewResult.Changes
        assertTrue(changes.summary.contains("Morning Ride [1] [2]"))
    }

    @Test
    fun `applyPending sends update through StravaApi and writes log`() {
        seedUser()
        val action = botActions.createAction(1L, "Rename")
        botActions.updateActionCode(1L, action.id, "activity.name = 'New Name';")
        botActions.toggleAction(1L, action.id)
        seedActivity(100L)

        // Create pending apply via preview
        botActions.previewApplySingle(1L, "100", action.id)

        val result = botActions.applyPending(1L, "100")

        assertTrue(result is BotActions.ApplyResult.Success)
        assertEquals(1, fakeApi.updateCalls.size)
        assertEquals(100L, fakeApi.updateCalls[0].activityId)

        val logs = dataStore.getApplyLogs(1L, 10)
        assertTrue(logs.any { it.result == ApplyResult.APPLIED })
    }

    @Test
    fun `applyPending without activity write scope returns error`() {
        seedUser(scope = "activity:read")
        val result = botActions.applyPending(1L, "100")

        assertTrue(result is BotActions.ApplyResult.Error)
        assertTrue((result as BotActions.ApplyResult.Error).message.contains("activity:write"))
    }

    // --- Pending edit handling ---

    @Test
    fun `tryHandlePendingEdit routes code edit correctly`() {
        seedUser()
        val action = botActions.createAction(1L, "Test")
        botActions.beginActionEdit(1L, action.id)

        val result = botActions.tryHandlePendingEdit(1L, "activity.name = 'edited';")

        assertTrue(result is BotActions.PendingEditResult.CodeUpdated)
        assertEquals("Test", (result as BotActions.PendingEditResult.CodeUpdated).actionName)
        assertEquals("activity.name = 'edited';", botActions.getAction(1L, action.id)!!.code)
    }

    @Test
    fun `tryHandlePendingEdit returns SyntaxError for invalid code`() {
        seedUser()
        val action = botActions.createAction(1L, "Test")
        botActions.beginActionEdit(1L, action.id)

        val result = botActions.tryHandlePendingEdit(1L, "if ( }")

        assertTrue(result is BotActions.PendingEditResult.SyntaxError)
    }

    @Test
    fun `tryHandlePendingEdit routes create name then code stages`() {
        seedUser()
        botActions.beginActionCreate(1L)

        // Stage 1: name
        val nameResult = botActions.tryHandlePendingEdit(1L, "My New Action")
        assertTrue(nameResult is BotActions.PendingEditResult.NameReceived)
        assertEquals("My New Action", (nameResult as BotActions.PendingEditResult.NameReceived).name)

        // Stage 2: code
        val codeResult = botActions.tryHandlePendingEdit(1L, "activity.mute = true;")
        assertTrue(codeResult is BotActions.PendingEditResult.ActionCreated)

        // Verify action was created with custom code
        val user = dataStore.getUser(1L)!!
        val created = user.actions.last()
        assertEquals("My New Action", created.name)
        assertEquals("activity.mute = true;", created.code)
    }

    @Test
    fun `tryHandlePendingEdit returns null when no pending state`() {
        seedUser()
        assertNull(botActions.tryHandlePendingEdit(1L, "some text"))
    }

    @Test
    fun `tryHandlePendingEdit returns null for slash commands`() {
        seedUser()
        val action = botActions.createAction(1L, "Test")
        botActions.beginActionEdit(1L, action.id)

        assertNull(botActions.tryHandlePendingEdit(1L, "/help"))
    }

    // --- Description editing ---

    @Test
    fun `updateActionDescription updates description in DataStore`() {
        seedUser()
        val action = botActions.createAction(1L, "Test")
        val updated = botActions.updateActionDescription(1L, action.id, "My description")

        assertTrue(updated)
        val stored = botActions.getAction(1L, action.id)!!
        assertEquals("My description", stored.description)
    }

    @Test
    fun `updateActionDescription returns false for nonexistent action`() {
        seedUser()
        assertFalse(botActions.updateActionDescription(1L, "nonexistent", "desc"))
    }

    @Test
    fun `tryHandlePendingEdit routes description edit correctly`() {
        seedUser()
        val action = botActions.createAction(1L, "Test")
        botActions.beginActionEdit(1L, action.id, "description")

        val result = botActions.tryHandlePendingEdit(1L, "My new description")

        assertTrue(result is BotActions.PendingEditResult.DescriptionUpdated)
        assertEquals("My new description", botActions.getAction(1L, action.id)!!.description)
    }
}

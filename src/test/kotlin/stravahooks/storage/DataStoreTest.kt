package stravahooks.storage

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataStoreTest {
    private fun createTempStore(): DataStore {
        val dir = Files.createTempDirectory("stravahooks-test")
        return DataStore(dir.resolve("test.db.json"))
    }

    // --- load ---

    @Test
    fun `load returns empty state for nonexistent file`() {
        val store = createTempStore()
        val state = store.load()

        assertTrue(state.users.isEmpty())
        assertTrue(state.oauthStates.isEmpty())
        assertTrue(state.applyLogs.isEmpty())
    }

    // --- save + load round-trip ---

    @Test
    fun `save and load round-trips state`() {
        val store = createTempStore()
        val user = StoredUser(
            telegramUserId = 42L,
            strava = StravaAccount(
                accessToken = "tok",
                refreshToken = "ref",
                expiresAt = 999L,
                scope = "read",
                athleteId = 1L,
                athleteName = "Test"
            ),
            actions = listOf(
                ActionDefinition(
                    id = "a1",
                    name = "Action 1",
                    code = "activity.mute = true;",
                    enabled = true,
                    order = 1,
                    createdAt = 100L,
                    updatedAt = 100L
                )
            )
        )
        val state = StoredState(users = listOf(user))

        store.save(state)
        val loaded = store.load()

        assertEquals(1, loaded.users.size)
        assertEquals(42L, loaded.users[0].telegramUserId)
        assertEquals("tok", loaded.users[0].strava?.accessToken)
        assertEquals(1, loaded.users[0].actions.size)
        assertEquals("a1", loaded.users[0].actions[0].id)
        assertEquals(true, loaded.users[0].actions[0].enabled)
    }

    // --- upsertUser ---

    @Test
    fun `upsertUser creates new user`() {
        val store = createTempStore()

        val result = store.upsertUser(42L) { user ->
            user.copy(lastPolledAt = 100L)
        }

        assertEquals(42L, result.telegramUserId)
        assertEquals(100L, result.lastPolledAt)

        val loaded = store.getUser(42L)
        assertNotNull(loaded)
        assertEquals(100L, loaded.lastPolledAt)
    }

    @Test
    fun `upsertUser updates existing user`() {
        val store = createTempStore()

        store.upsertUser(42L) { user ->
            user.copy(lastPolledAt = 100L)
        }
        store.upsertUser(42L) { user ->
            user.copy(lastPolledAt = 200L)
        }

        val loaded = store.getUser(42L)
        assertNotNull(loaded)
        assertEquals(200L, loaded.lastPolledAt)

        // Should still be only one user
        val state = store.load()
        assertEquals(1, state.users.size)
    }

    // --- OAuth state management ---

    @Test
    fun `putOAuthState and consumeOAuthState returns userId for valid nonce`() {
        val store = createTempStore()
        val entry = OAuthStateEntry(nonce = "abc123", telegramUserId = 42L, issuedAt = 1000L)

        store.putOAuthState(entry)
        val result = store.consumeOAuthState("abc123", ttlMillis = 600_000L, nowMillis = 1500L)

        assertEquals(42L, result)
    }

    @Test
    fun `consumeOAuthState returns null for expired nonce`() {
        val store = createTempStore()
        val entry = OAuthStateEntry(nonce = "abc123", telegramUserId = 42L, issuedAt = 1000L)

        store.putOAuthState(entry)
        // TTL is 500ms, current time is 2000ms => 2000-1000=1000 > 500 => expired
        val result = store.consumeOAuthState("abc123", ttlMillis = 500L, nowMillis = 2000L)

        assertNull(result)
    }

    @Test
    fun `consumeOAuthState returns null on double-consume`() {
        val store = createTempStore()
        val entry = OAuthStateEntry(nonce = "abc123", telegramUserId = 42L, issuedAt = 1000L)

        store.putOAuthState(entry)
        val first = store.consumeOAuthState("abc123", ttlMillis = 600_000L, nowMillis = 1500L)
        val second = store.consumeOAuthState("abc123", ttlMillis = 600_000L, nowMillis = 1500L)

        assertEquals(42L, first)
        assertNull(second)
    }

    @Test
    fun `consumeOAuthState cleans up expired entries`() {
        val store = createTempStore()
        store.putOAuthState(OAuthStateEntry(nonce = "old", telegramUserId = 1L, issuedAt = 100L))
        store.putOAuthState(OAuthStateEntry(nonce = "new", telegramUserId = 2L, issuedAt = 5000L))

        // Consume "new" with a TTL that makes "old" expired
        store.consumeOAuthState("new", ttlMillis = 1000L, nowMillis = 5500L)

        // "old" should have been cleaned up
        val state = store.load()
        assertTrue(state.oauthStates.isEmpty())
    }

    // --- Apply logs ---

    @Test
    fun `appendApplyLog and getApplyLogs returns logs in reverse order`() {
        val store = createTempStore()
        val log1 = makeLogEntry(telegramUserId = 42L, activityId = 1L, timestamp = 100L)
        val log2 = makeLogEntry(telegramUserId = 42L, activityId = 2L, timestamp = 200L)

        store.appendApplyLog(log1)
        store.appendApplyLog(log2)

        val logs = store.getApplyLogs(42L, limit = 10)

        assertEquals(2, logs.size)
        assertEquals(2L, logs[0].activityId)  // most recent first
        assertEquals(1L, logs[1].activityId)
    }

    @Test
    fun `getApplyLogs respects limit`() {
        val store = createTempStore()
        repeat(5) { i ->
            store.appendApplyLog(makeLogEntry(telegramUserId = 42L, activityId = i.toLong(), timestamp = i.toLong()))
        }

        val logs = store.getApplyLogs(42L, limit = 2)

        assertEquals(2, logs.size)
    }

    @Test
    fun `getApplyLogs filters by user`() {
        val store = createTempStore()
        store.appendApplyLog(makeLogEntry(telegramUserId = 42L, activityId = 1L))
        store.appendApplyLog(makeLogEntry(telegramUserId = 99L, activityId = 2L))
        store.appendApplyLog(makeLogEntry(telegramUserId = 42L, activityId = 3L))

        val logs = store.getApplyLogs(42L, limit = 10)

        assertEquals(2, logs.size)
        assertTrue(logs.all { it.telegramUserId == 42L })
    }

    @Test
    fun `appendApplyLog caps at MAX_LOG_ENTRIES`() {
        val store = createTempStore()
        // Append 210 entries
        repeat(210) { i ->
            store.appendApplyLog(makeLogEntry(telegramUserId = 1L, activityId = i.toLong(), timestamp = i.toLong()))
        }

        val state = store.load()
        assertEquals(200, state.applyLogs.size)
        // Should keep the last 200, so first entry should be #10
        assertEquals(10L, state.applyLogs.first().activityId)
    }

    private fun makeLogEntry(
        telegramUserId: Long = 42L,
        activityId: Long = 1L,
        timestamp: Long = 100L
    ) = ApplyLogEntry(
        timestamp = timestamp,
        telegramUserId = telegramUserId,
        activityId = activityId,
        actionIds = listOf("a1"),
        actionNames = listOf("Action 1"),
        mode = ApplyMode.POLL,
        summary = "test",
        changes = emptyMap(),
        logs = emptyList(),
        result = ApplyResult.APPLIED
    )
}

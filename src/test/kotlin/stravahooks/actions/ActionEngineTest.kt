package stravahooks.actions

import stravahooks.storage.ActivityUpdate
import stravahooks.strava.StravaActivity
import stravahooks.strava.StravaGear
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActionEngineTest {
    private val engine = ActionEngine()

    // --- normalizeActivity ---

    @Test
    fun `normalizeActivity maps all fields with correct keys`() {
        val activity = StravaActivity(
            id = 123L,
            name = "Morning Run",
            type = "Run",
            startDate = "2024-01-15T08:00:00Z",
            distance = 5000.0,
            movingTime = 1800,
            elapsedTime = 2000,
            description = "Nice run",
            commute = false,
            trainer = false,
            mute = false,
            visibility = "everyone",
            gearId = "g123",
            gear = StravaGear(id = "g123", name = "Running Shoes"),
            totalElevationGain = 50.0,
            averageSpeed = 2.78,  // m/s
            maxSpeed = 4.17,      // m/s
            averageHeartrate = 150.0,
            maxHeartrate = 180.0,
            averageCadence = 85.0
        )

        val normalized = engine.normalizeActivity(activity)

        assertEquals(123L, normalized["id"])
        assertEquals("Morning Run", normalized["name"])
        assertEquals("Run", normalized["type"])
        assertEquals("2024-01-15T08:00:00Z", normalized["start_time"])
        assertEquals(5000.0, normalized["distance_m"])
        assertEquals(1800, normalized["moving_time_s"])
        assertEquals(2000, normalized["elapsed_time_s"])
        assertEquals("Nice run", normalized["description"])
        assertEquals(false, normalized["commute"])
        assertEquals(false, normalized["trainer"])
        assertEquals(false, normalized["mute"])
        assertEquals("everyone", normalized["visibility"])
        assertEquals("g123", normalized["gear_id"])
        assertEquals("Running Shoes", normalized["gear_name"])
        assertEquals(50.0, normalized["elevation_gain_m"])
        assertEquals(150.0, normalized["average_hr_bpm"])
        assertEquals(180.0, normalized["max_hr_bpm"])
        assertEquals(85.0, normalized["average_cadence_rpm"])
    }

    @Test
    fun `normalizeActivity converts speed from meters per second to kph`() {
        val activity = StravaActivity(averageSpeed = 2.78, maxSpeed = 4.17)
        val normalized = engine.normalizeActivity(activity)

        // 2.78 m/s * 3.6 = 10.008 kph
        assertEquals(2.78 * 3.6, normalized["average_speed_kph"])
        assertEquals(4.17 * 3.6, normalized["max_speed_kph"])
    }

    @Test
    fun `normalizeActivity handles null optional fields`() {
        val activity = StravaActivity(id = 1L, name = "Test")
        val normalized = engine.normalizeActivity(activity)

        assertNull(normalized["description"])
        assertNull(normalized["gear_id"])
        assertNull(normalized["gear_name"])
        assertNull(normalized["elevation_gain_m"])
        assertNull(normalized["average_speed_kph"])
        assertNull(normalized["max_speed_kph"])
        assertNull(normalized["average_hr_bpm"])
        assertNull(normalized["max_hr_bpm"])
        assertNull(normalized["average_cadence_rpm"])
    }

    // --- snapshotWritable ---

    @Test
    fun `snapshotWritable returns only the 6 writable fields`() {
        val activity = mapOf(
            "id" to 1L,
            "type" to "Run",
            "name" to "Test",
            "description" to "desc",
            "commute" to false,
            "trainer" to true,
            "mute" to false,
            "gear_id" to "g1",
            "distance_m" to 5000.0,
            "visibility" to "everyone"
        )

        val snapshot = engine.snapshotWritable(activity)

        assertEquals(6, snapshot.size)
        assertEquals("Test", snapshot["name"])
        assertEquals("desc", snapshot["description"])
        assertEquals(false, snapshot["commute"])
        assertEquals(true, snapshot["trainer"])
        assertEquals(false, snapshot["mute"])
        assertEquals("g1", snapshot["gear_id"])
        // Should not include non-writable fields
        assertTrue("id" !in snapshot)
        assertTrue("type" !in snapshot)
        assertTrue("distance_m" !in snapshot)
    }

    // --- diffWritable ---

    @Test
    fun `diffWritable returns empty map when no changes`() {
        val before = mapOf("name" to "Run", "mute" to false, "commute" to false,
            "trainer" to false, "description" to null, "gear_id" to null)
        val after = mapOf("name" to "Run", "mute" to false, "commute" to false,
            "trainer" to false, "description" to null, "gear_id" to null)

        val diff = engine.diffWritable(before, after)

        assertTrue(diff.isEmpty())
    }

    @Test
    fun `diffWritable detects changed fields only`() {
        val before = mapOf("name" to "Run", "mute" to false, "commute" to false,
            "trainer" to false, "description" to null, "gear_id" to null)
        val after = mapOf("name" to "Run!", "mute" to true, "commute" to false,
            "trainer" to false, "description" to null, "gear_id" to null)

        val diff = engine.diffWritable(before, after)

        assertEquals(2, diff.size)
        assertEquals("Run" to "Run!", diff["name"])
        assertEquals(false to true, diff["mute"])
    }

    @Test
    fun `diffWritable handles null to value transitions`() {
        val before = mapOf("name" to "Run", "description" to null,
            "commute" to false, "trainer" to false, "mute" to false, "gear_id" to null)
        val after = mapOf("name" to "Run", "description" to "Added desc",
            "commute" to false, "trainer" to false, "mute" to false, "gear_id" to "g1")

        val diff = engine.diffWritable(before, after)

        assertEquals(2, diff.size)
        assertEquals(null to "Added desc", diff["description"])
        assertEquals(null to "g1", diff["gear_id"])
    }

    @Test
    fun `diffWritable handles value to null transitions`() {
        val before = mapOf("name" to "Run", "description" to "Some desc",
            "commute" to false, "trainer" to false, "mute" to false, "gear_id" to "g1")
        val after = mapOf("name" to "Run", "description" to null,
            "commute" to false, "trainer" to false, "mute" to false, "gear_id" to null)

        val diff = engine.diffWritable(before, after)

        assertEquals(2, diff.size)
        assertEquals("Some desc" to null, diff["description"])
        assertEquals("g1" to null, diff["gear_id"])
    }

    // --- buildUpdate ---

    @Test
    fun `buildUpdate maps change pairs to ActivityUpdate`() {
        val changes = mapOf(
            "name" to ("Old" as Any? to "New" as Any?),
            "mute" to (false as Any? to true as Any?),
            "gear_id" to (null as Any? to "g1" as Any?)
        )

        val update = engine.buildUpdate(changes)

        assertEquals(setOf("name", "mute", "gear_id"), update.fields)
        assertEquals("New", update.name)
        assertEquals(true, update.mute)
        assertEquals("g1", update.gearId)
        assertNull(update.description)
        assertNull(update.commute)
        assertNull(update.trainer)
    }

    // --- buildUpdateBody ---

    @Test
    fun `buildUpdateBody only includes fields in update fields set`() {
        val update = ActivityUpdate(
            fields = setOf("name", "mute"),
            name = "New Name",
            mute = true,
            description = "Should not appear"
        )

        val body = engine.buildUpdateBody(update)

        assertEquals(2, body.size)
        assertEquals("New Name", body["name"])
        assertEquals(true, body["mute"])
        assertTrue("description" !in body)
    }

    @Test
    fun `buildUpdateBody includes all 6 writable fields when all changed`() {
        val update = ActivityUpdate(
            fields = setOf("name", "description", "commute", "trainer", "mute", "gear_id"),
            name = "N",
            description = "D",
            commute = true,
            trainer = false,
            mute = true,
            gearId = "g1"
        )

        val body = engine.buildUpdateBody(update)

        assertEquals(6, body.size)
        assertEquals("N", body["name"])
        assertEquals("D", body["description"])
        assertEquals(true, body["commute"])
        assertEquals(false, body["trainer"])
        assertEquals(true, body["mute"])
        assertEquals("g1", body["gear_id"])
    }

    // --- toChangePairs / toChangePairsFromSummary round-trip ---

    @Test
    fun `toChangePairs converts pairs to ChangePair objects`() {
        val changes = mapOf(
            "name" to ("Old" as Any? to "New" as Any?),
            "mute" to (false as Any? to true as Any?)
        )

        val pairs = engine.toChangePairs(changes)

        assertEquals("Old", pairs["name"]?.before)
        assertEquals("New", pairs["name"]?.after)
        assertEquals("false", pairs["mute"]?.before)
        assertEquals("true", pairs["mute"]?.after)
    }

    @Test
    fun `toChangePairsFromSummary parses summary text correctly`() {
        val summary = "name: Old -> New\nmute: false -> true"

        val pairs = engine.toChangePairsFromSummary(summary)

        assertEquals("Old", pairs["name"]?.before)
        assertEquals("New", pairs["name"]?.after)
        assertEquals("false", pairs["mute"]?.before)
        assertEquals("true", pairs["mute"]?.after)
    }

    // --- validateChanges ---

    @Test
    fun `validateChanges returns valid when only writable fields changed`() {
        val before = mapOf("name" to "Old", "mute" to false, "type" to "Run")
        val after = mapOf("name" to "New", "mute" to true, "type" to "Run")

        val result = engine.validateChanges(before, after)

        assertTrue(result.isValid)
    }

    @Test
    fun `validateChanges detects readonly field write`() {
        val before = mapOf("name" to "Old", "type" to "Run", "distance_m" to 5000.0)
        val after = mapOf("name" to "Old", "type" to "Walk", "distance_m" to 5000.0)

        val result = engine.validateChanges(before, after)

        assertTrue(!result.isValid)
        assertTrue("type" in result.readonlyWrites)
    }

    @Test
    fun `validateChanges detects empty gear_id as invalid`() {
        val before = mapOf("gear_id" to "g1")
        val after = mapOf("gear_id" to "")

        val result = engine.validateChanges(before, after)

        assertTrue(!result.isValid)
        assertTrue(result.invalidValues.any { it.contains("gear_id") })
    }

    @Test
    fun `validateChanges allows gear_id null (clearing gear)`() {
        val before = mapOf<String, Any?>("gear_id" to "g1")
        val after = mapOf<String, Any?>("gear_id" to null)

        val result = engine.validateChanges(before, after)

        assertTrue(result.isValid)
    }

    @Test
    fun `validateChanges detects multiple violations at once`() {
        val before = mapOf<String, Any?>("type" to "Run", "distance_m" to 5000.0, "gear_id" to "g1")
        val after = mapOf<String, Any?>("type" to "Walk", "distance_m" to 999.0, "gear_id" to "")

        val result = engine.validateChanges(before, after)

        assertTrue(!result.isValid)
        assertTrue(result.readonlyWrites.containsAll(listOf("type", "distance_m")))
        assertTrue(result.invalidValues.isNotEmpty())
    }

    @Test
    fun `validateChanges integration with ActionRunner`() {
        val runner = ActionRunner()
        val activity = mutableMapOf<String, Any?>(
            "name" to "Run",
            "type" to "Run",
            "gear_id" to "g1"
        )
        val before = activity.toMap()
        val code = "activity.type = 'Walk'; activity.gear_id = '';"
        runner.run(code, activity)

        val result = engine.validateChanges(before, activity)

        assertTrue(!result.isValid)
        assertTrue("type" in result.readonlyWrites)
        assertTrue(result.invalidValues.any { it.contains("gear_id") })
    }

    @Test
    fun `toChangePairs and toChangePairsFromSummary round-trip`() {
        val changes = mapOf(
            "name" to ("Morning Run" as Any? to "Evening Run" as Any?),
            "commute" to (false as Any? to true as Any?)
        )

        val pairs = engine.toChangePairs(changes)
        val summary = changes.entries.joinToString("\n") { (key, value) ->
            val before = value.first?.toString() ?: "null"
            val after = value.second?.toString() ?: "null"
            "$key: $before -> $after"
        }
        val parsed = engine.toChangePairsFromSummary(summary)

        assertEquals(pairs["name"]?.before, parsed["name"]?.before)
        assertEquals(pairs["name"]?.after, parsed["name"]?.after)
        assertEquals(pairs["commute"]?.before, parsed["commute"]?.before)
        assertEquals(pairs["commute"]?.after, parsed["commute"]?.after)
    }
}

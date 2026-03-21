package stravahooks.actions

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class ActionTemplatesTest {
    private val runner = ActionRunner()

    @Test
    fun `all templates pass syntax validation`() {
        ActionTemplates.ALL.forEach { template ->
            val error = runner.validateSyntax(template.code)
            assertNull(error, "Template ${template.name} has syntax error: $error")
        }
    }

    @Test
    fun `all templates run without error on sample activity`() {
        ActionTemplates.ALL.forEach { template ->
            val activity = mutableMapOf<String, Any?>(
                "type" to "Ride",
                "name" to "Morning Ride",
                "distance_m" to 10000.0,
                "average_speed_kph" to 25.0,
                "description" to null,
                "mute" to false,
                "commute" to false,
                "gear_id" to null
            )
            val result = runner.run(template.code, activity)
            assertNull(result.error, "Template ${template.name} produced error: ${result.error}")
        }
    }

    @Test
    fun `SHORT_RIDE_MUTE mutes short ride`() {
        val activity = mutableMapOf<String, Any?>(
            "type" to "Ride",
            "name" to "Short Ride",
            "distance_m" to 3000.0,
            "mute" to false,
            "commute" to false
        )
        val result = runner.run(ActionTemplates.SHORT_RIDE_MUTE.code, activity)
        assertNull(result.error)
        assertEquals(true, activity["mute"])
        assertEquals(true, activity["commute"])
    }

    @Test
    fun `SHORT_RIDE_MUTE does not affect runs`() {
        val activity = mutableMapOf<String, Any?>(
            "type" to "Run",
            "name" to "Short Run",
            "distance_m" to 3000.0,
            "mute" to false,
            "commute" to false
        )
        val result = runner.run(ActionTemplates.SHORT_RIDE_MUTE.code, activity)
        assertNull(result.error)
        assertEquals(false, activity["mute"])
        assertEquals(false, activity["commute"])
    }

    @Test
    fun `CONDITION_DESCRIPTION appends stats to description`() {
        val activity = mutableMapOf<String, Any?>(
            "type" to "Run",
            "name" to "Morning Run",
            "distance_m" to 10000.0,
            "average_speed_kph" to 12.5,
            "description" to null
        )
        val result = runner.run(ActionTemplates.CONDITION_DESCRIPTION.code, activity)
        assertNull(result.error)
        val desc = activity["description"] as? String
        assertTrue(desc != null && desc.contains("10.0 km"), "Description should contain distance: $desc")
        assertTrue(desc.contains("12.5 km/h"), "Description should contain speed: $desc")
    }

    @Test
    fun `GEAR_SELECTION sets gear for ride`() {
        val activity = mutableMapOf<String, Any?>(
            "type" to "Ride",
            "name" to "Morning Ride",
            "gear_id" to null
        )
        val result = runner.run(ActionTemplates.GEAR_SELECTION.code, activity)
        assertNull(result.error)
        assertTrue(activity["gear_id"] != null, "gear_id should be set for rides")
    }
}

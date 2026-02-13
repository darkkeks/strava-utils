package stravahooks.actions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActionRunnerTest {
    private val runner = ActionRunner()

    @Test
    fun `runs action and mutates activity`() {
        val activity = mutableMapOf<String, Any?>(
            "name" to "Ride",
            "mute" to false
        )
        val code = """
            function action(activity) {
              activity.mute = true;
              activity.name = activity.name + "!";
            }
        """.trimIndent()

        val result = runner.run(code, activity)

        assertNull(result.error)
        assertEquals(true, activity["mute"])
        assertEquals("Ride!", activity["name"])
    }

    @Test
    fun `runs action from body only`() {
        val activity = mutableMapOf<String, Any?>(
            "name" to "Run",
            "commute" to false
        )
        val code = """
            activity.commute = true;
            activity.name = activity.name + " ok";
        """.trimIndent()

        val result = runner.run(code, activity)

        assertNull(result.error)
        assertEquals(true, activity["commute"])
        assertEquals("Run ok", activity["name"])
    }

    @Test
    fun `wraps code when action function missing`() {
        val activity = mutableMapOf<String, Any?>("name" to "Ride")
        val code = """
            function notAction(activity) {
              activity.name = "Nope";
            }
        """.trimIndent()

        val result = runner.run(code, activity)

        assertNull(result.error)
        assertEquals("Ride", activity["name"])
    }
}

package stravahooks.actions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `returns error for syntax error in code`() {
        val activity = mutableMapOf<String, Any?>("name" to "Ride")
        val code = """
            function action(activity) {
              if ( }
            }
        """.trimIndent()

        val result = runner.run(code, activity)

        assertNotNull(result.error)
    }

    @Test
    fun `returns error for runtime exception`() {
        val activity = mutableMapOf<String, Any?>("name" to "Ride")
        val code = """
            function action(activity) {
              null.toString();
            }
        """.trimIndent()

        val result = runner.run(code, activity)

        assertNotNull(result.error)
    }

    @Test
    fun `captures console log output`() {
        val activity = mutableMapOf<String, Any?>("name" to "Ride")
        val code = """
            function action(activity) {
              console.log("hello", "world");
              console.log("second line");
            }
        """.trimIndent()

        val result = runner.run(code, activity)

        assertNull(result.error)
        assertEquals(2, result.logs.size)
        assertEquals("hello world", result.logs[0])
        assertEquals("second line", result.logs[1])
    }

    @Test
    fun `setting field to null in JS results in Kotlin null`() {
        val activity = mutableMapOf<String, Any?>("description" to "some text")
        val code = """
            function action(activity) {
              activity.description = null;
            }
        """.trimIndent()

        val result = runner.run(code, activity)

        assertNull(result.error)
        assertNull(activity["description"])
    }

    // --- validateSyntax ---

    @Test
    fun `validateSyntax returns null for valid function`() {
        val code = "function action(activity) { activity.name = 'test'; }"
        assertNull(runner.validateSyntax(code))
    }

    @Test
    fun `validateSyntax returns error for syntax error`() {
        val code = "function action(activity) { if ( } }"
        val error = runner.validateSyntax(code)
        assertNotNull(error)
    }

    @Test
    fun `validateSyntax returns null for valid body-only code`() {
        val code = "activity.name = 'test';"
        assertNull(runner.validateSyntax(code))
    }

    @Test
    fun `validateSyntax returns error for body-only code with syntax error`() {
        val code = "if ( }"
        val error = runner.validateSyntax(code)
        assertNotNull(error)
    }

    // --- instruction limit ---

    @Test
    fun `infinite loop is stopped by instruction limit`() {
        val runner = ActionRunner(maxInstructions = 10_000)
        val activity = mutableMapOf<String, Any?>("name" to "Ride")
        val code = "function action(activity) { while(true) {} }"

        val result = runner.run(code, activity)

        assertNotNull(result.error)
        assertTrue(result.error!!.contains("instruction limit"))
    }

    @Test
    fun `normal script succeeds under instruction limit`() {
        val runner = ActionRunner(maxInstructions = 100_000)
        val activity = mutableMapOf<String, Any?>("name" to "Ride")
        val code = "function action(activity) { activity.name = 'OK'; }"

        val result = runner.run(code, activity)

        assertNull(result.error)
        assertEquals("OK", activity["name"])
    }

    @Test
    fun `new fields added in JS appear in activity map`() {
        val activity = mutableMapOf<String, Any?>("name" to "Ride")
        val code = """
            function action(activity) {
              activity.new_field = "hello";
            }
        """.trimIndent()

        val result = runner.run(code, activity)

        assertNull(result.error)
        assertEquals("hello", activity["new_field"])
    }
}

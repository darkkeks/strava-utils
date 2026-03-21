package stravahooks.actions

import org.mozilla.javascript.Context
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

data class ActionRunResult(
    val error: String? = null,
    val logs: List<String> = emptyList()
)

class ActionRunner(private val maxInstructions: Int = DEFAULT_MAX_INSTRUCTIONS) {
    private val contextFactory = InstructionLimitContextFactory(maxInstructions)

    fun run(code: String, activity: MutableMap<String, Any?>): ActionRunResult {
        return try {
            val source = prepareSource(code)
            val logs = mutableListOf<String>()
            val context = contextFactory.enterContext()
            try {
                val scope: Scriptable = context.initStandardObjects()
                val activityObject = context.newObject(scope)
                activity.forEach { (key, value) ->
                    ScriptableObject.putProperty(activityObject, key, value)
                }
                val console = context.newObject(scope)
                val logFn = object : BaseFunction() {
                    override fun call(
                        cx: Context,
                        scope: Scriptable,
                        thisObj: Scriptable,
                        args: Array<out Any>
                    ): Any? {
                        val line = args.joinToString(" ") { arg ->
                            val converted = Context.jsToJava(arg, Any::class.java)
                            converted?.toString() ?: "null"
                        }
                        logs.add(line)
                        return org.mozilla.javascript.Undefined.instance
                    }
                }
                ScriptableObject.putProperty(console, "log", logFn)
                scope.put("console", scope, console)
                scope.put("activity", scope, activityObject)
                context.evaluateString(scope, source, "action.js", 1, null)
                val fnObj = scope.get("action", scope)
                if (fnObj !is Function) {
                    return ActionRunResult(error = "No action(activity) function found.", logs = logs)
                }
                fnObj.call(context, scope, scope, arrayOf(activityObject))
                val ids = activityObject.ids
                ids.forEach { id ->
                    if (id is String) {
                        val value = ScriptableObject.getProperty(activityObject, id)
                        val converted = Context.jsToJava(value, Any::class.java)
                        activity[id] = if (converted is org.mozilla.javascript.Undefined) {
                            null
                        } else {
                            converted
                        }
                    }
                }
            } finally {
                Context.exit()
            }
            ActionRunResult(logs = logs)
        } catch (e: Throwable) {
            ActionRunResult(error = e.message ?: "unknown error")
        }
    }

    fun validateSyntax(code: String): String? {
        return try {
            val source = prepareSource(code)
            val context = contextFactory.enterContext()
            try {
                context.compileString(source, "action.js", 1, null)
            } finally {
                Context.exit()
            }
            null
        } catch (e: Exception) {
            e.message ?: "Syntax error"
        }
    }

    private fun prepareSource(code: String): String {
        val trimmed = code.trim()
        return if (Regex("""^\s*function\s+action\s*\(""").containsMatchIn(trimmed)) {
            trimmed
        } else {
            "function action(activity) {\n$trimmed\n}\n"
        }
    }

    companion object {
        const val DEFAULT_MAX_INSTRUCTIONS = 100_000
    }
}

private class InstructionLimitContextFactory(private val maxInstructions: Int) : ContextFactory() {
    companion object {
        private const val INSTRUCTION_OBSERVER_THRESHOLD = 10_000
    }
    override fun observeInstructionCount(cx: Context, instructionCount: Int) {
        if (instructionCount > maxInstructions) {
            throw Error("Script execution exceeded $maxInstructions instruction limit")
        }
    }

    override fun makeContext(): Context {
        val cx = super.makeContext()
        cx.instructionObserverThreshold = INSTRUCTION_OBSERVER_THRESHOLD
        cx.setClassShutter { _ -> false }
        return cx
    }
}

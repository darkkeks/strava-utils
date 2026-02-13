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

class ActionRunner {
    fun run(code: String, activity: MutableMap<String, Any?>): ActionRunResult {
        return try {
            val source = prepareSource(code)
            val logs = mutableListOf<String>()
            val context = ContextFactory.getGlobal().enterContext()
            try {
                val scope: Scriptable = context.initStandardObjects()
                val activityObject = context.newObject(scope)
                activity.forEach { (key, value) ->
                    ScriptableObject.putProperty(activityObject, key, value)
                }
                val console = context.newObject(scope)
                val logFn = object : BaseFunction() {
                    override fun call(
                        cx: org.mozilla.javascript.Context,
                        scope: Scriptable,
                        thisObj: Scriptable,
                        args: Array<out Any>
                    ): Any? {
                        val line = args.joinToString(" ") { arg ->
                            val converted = org.mozilla.javascript.Context.jsToJava(arg, Any::class.java)
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
        } catch (e: Exception) {
            ActionRunResult(error = e.message ?: "unknown error")
        }
    }

    private fun prepareSource(code: String): String {
        val trimmed = code.trim()
        return if (trimmed.contains("function action")) {
            trimmed
        } else {
            "function action(activity) {\n$trimmed\n}\n"
        }
    }
}

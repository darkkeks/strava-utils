package stravahooks.actions

import stravahooks.storage.ActionDefinition
import stravahooks.storage.ActivityUpdate
import stravahooks.strava.StravaActivity

class ActionEngine(private val runner: ActionRunner = ActionRunner()) {

    data class ActionRunOutput(
        val errors: List<String>,
        val logs: List<String>
    )

    fun validateSyntax(code: String): String? = runner.validateSyntax(code)

    fun runActions(actions: List<ActionDefinition>, activity: MutableMap<String, Any?>): ActionRunOutput {
        val errors = mutableListOf<String>()
        val logs = mutableListOf<String>()
        actions.forEach { action ->
            val result = runner.run(action.code, activity)
            if (result.logs.isNotEmpty()) {
                result.logs.forEach { line ->
                    logs.add("${action.name}: $line")
                }
            }
            val error = result.error
            if (error != null) {
                errors.add("${action.name}: $error")
            }
        }
        return ActionRunOutput(errors = errors, logs = logs)
    }

    fun normalizeActivity(activity: StravaActivity): Map<String, Any?> = buildMap {
        put("id", activity.id)
        put("type", activity.type)
        put("name", activity.name)
        put("start_time", activity.startDate)
        put("distance_m", activity.distance)
        put("moving_time_s", activity.movingTime)
        put("elapsed_time_s", activity.elapsedTime)
        put("description", activity.description)
        put("commute", activity.commute)
        put("trainer", activity.trainer)
        put("mute", activity.mute)
        put("visibility", activity.visibility)
        put("gear_id", activity.gearId)
        put("gear_name", activity.gear?.name)
        put("elevation_gain_m", activity.totalElevationGain)
        put("average_speed_kph", activity.averageSpeed?.times(MS_TO_KPH))
        put("max_speed_kph", activity.maxSpeed?.times(MS_TO_KPH))
        put("average_hr_bpm", activity.averageHeartrate)
        put("max_hr_bpm", activity.maxHeartrate)
        put("average_cadence_rpm", activity.averageCadence)
    }

    fun snapshotWritable(activity: Map<String, Any?>): Map<String, Any?> {
        return WRITABLE_FIELDS.associateWith { activity[it] }
    }

    fun diffWritable(
        before: Map<String, Any?>,
        after: Map<String, Any?>
    ): Map<String, Pair<Any?, Any?>> {
        val changes = mutableMapOf<String, Pair<Any?, Any?>>()
        WRITABLE_FIELDS.forEach { key ->
            val a = before[key]
            val b = after[key]
            if (a != b) {
                changes[key] = a to b
            }
        }
        return changes
    }

    fun buildUpdate(changes: Map<String, Pair<Any?, Any?>>): ActivityUpdate {
        val fields = changes.keys
        return ActivityUpdate(
            fields = fields,
            name = changes["name"]?.second as? String,
            description = changes["description"]?.second as? String,
            commute = changes["commute"]?.second as? Boolean,
            trainer = changes["trainer"]?.second as? Boolean,
            mute = changes["mute"]?.second as? Boolean,
            gearId = changes["gear_id"]?.second as? String
        )
    }

    fun toChangePairs(changes: Map<String, Pair<Any?, Any?>>): Map<String, stravahooks.storage.ChangePair> {
        return changes.mapValues { (_, value) ->
            stravahooks.storage.ChangePair(
                before = value.first?.toString(),
                after = value.second?.toString()
            )
        }
    }

    fun toChangePairsFromSummary(summary: String): Map<String, stravahooks.storage.ChangePair> {
        val result = mutableMapOf<String, stravahooks.storage.ChangePair>()
        summary.lines().forEach { line ->
            val parts = line.split(":", limit = 2)
            if (parts.size != 2) return@forEach
            val key = parts[0].trim()
            val values = parts[1].split("->", limit = 2)
            if (values.size != 2) return@forEach
            val before = values[0].trim().ifBlank { null }
            val after = values[1].trim().ifBlank { null }
            result[key] = stravahooks.storage.ChangePair(before, after)
        }
        return result
    }

    fun buildUpdateBody(update: ActivityUpdate): Map<String, Any?> {
        val body = mutableMapOf<String, Any?>()
        if (update.fields.contains("name")) body["name"] = update.name
        if (update.fields.contains("description")) body["description"] = update.description
        if (update.fields.contains("commute")) body["commute"] = update.commute
        if (update.fields.contains("trainer")) body["trainer"] = update.trainer
        if (update.fields.contains("mute")) body["mute"] = update.mute
        if (update.fields.contains("gear_id")) body["gear_id"] = update.gearId
        return body
    }

    data class ValidationResult(
        val readonlyWrites: List<String> = emptyList(),
        val invalidValues: List<String> = emptyList()
    ) {
        val isValid get() = readonlyWrites.isEmpty() && invalidValues.isEmpty()
        fun errorMessage(): String {
            val parts = mutableListOf<String>()
            if (readonlyWrites.isNotEmpty()) {
                parts.add("Attempted to write read-only fields: ${readonlyWrites.joinToString(", ")}")
            }
            if (invalidValues.isNotEmpty()) {
                parts.add("Invalid values: ${invalidValues.joinToString(", ")}")
            }
            return parts.joinToString("\n")
        }
    }

    fun validateChanges(before: Map<String, Any?>, after: Map<String, Any?>): ValidationResult {
        val allKeys = before.keys union after.keys
        val readonlyWrites = mutableListOf<String>()
        val invalidValues = mutableListOf<String>()

        allKeys.forEach { key ->
            val a = before[key]
            val b = after[key]
            if (!valuesEqual(a, b) && key !in WRITABLE_FIELDS) {
                readonlyWrites.add(key)
            }
        }

        val gearId = after["gear_id"]
        if (gearId is String && gearId.isEmpty()) {
            invalidValues.add("gear_id cannot be empty string (use null to clear)")
        }

        return ValidationResult(readonlyWrites, invalidValues)
    }

    private fun valuesEqual(a: Any?, b: Any?): Boolean {
        if (a == b) return true
        if (a is Number && b is Number) return a.toDouble() == b.toDouble()
        return false
    }

    companion object {
        private const val MS_TO_KPH = 3.6
        val WRITABLE_FIELDS = setOf("name", "description", "commute", "trainer", "mute", "gear_id")
    }
}

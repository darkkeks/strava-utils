package stravahooks.actions

import stravahooks.storage.ActionDefinition
import stravahooks.storage.ActivityUpdate
import stravahooks.strava.StravaActivity

class ActionEngine(private val runner: ActionRunner = ActionRunner()) {
    data class ActionRunOutput(
        val errors: List<String>,
        val logs: List<String>
    )

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

    fun normalizeActivity(activity: StravaActivity): MutableMap<String, Any?> {
        val normalized = mutableMapOf<String, Any?>()
        normalized["id"] = activity.id
        normalized["type"] = activity.type
        normalized["name"] = activity.name
        normalized["start_time"] = activity.startDate
        normalized["distance_m"] = activity.distance
        normalized["moving_time_s"] = activity.movingTime
        normalized["elapsed_time_s"] = activity.elapsedTime
        normalized["description"] = activity.description
        normalized["commute"] = activity.commute
        normalized["trainer"] = activity.trainer
        normalized["mute"] = activity.mute
        normalized["visibility"] = activity.visibility
        normalized["gear_id"] = activity.gearId
        normalized["gear_name"] = activity.gear?.name
        normalized["elevation_gain_m"] = activity.totalElevationGain
        normalized["average_speed_kph"] = activity.averageSpeed?.times(3.6)
        normalized["max_speed_kph"] = activity.maxSpeed?.times(3.6)
        normalized["average_hr_bpm"] = activity.averageHeartrate
        normalized["max_hr_bpm"] = activity.maxHeartrate
        normalized["average_cadence_rpm"] = activity.averageCadence
        return normalized
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

    companion object {
        val WRITABLE_FIELDS = setOf("name", "description", "commute", "trainer", "mute", "gear_id")
    }
}

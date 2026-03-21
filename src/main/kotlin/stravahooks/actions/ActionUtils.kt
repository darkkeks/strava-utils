package stravahooks.actions

fun buildChangeSummary(changes: Map<String, Pair<Any?, Any?>>): String {
    return changes.entries.joinToString("\n") { (key, value) ->
        val before = value.first?.toString() ?: "null"
        val after = value.second?.toString() ?: "null"
        "$key: $before -> $after"
    }
}

fun activityUrl(activityId: Long): String {
    return "https://www.strava.com/activities/$activityId"
}

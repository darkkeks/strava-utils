package stravahooks.strava

class FakeStravaApi : StravaApi {
    val activities = mutableMapOf<Long, StravaActivity>()
    val summaries = mutableListOf<StravaSummaryActivity>()
    val updateCalls = mutableListOf<UpdateCall>()
    var updateResult: UpdateResult = UpdateResult(success = true)
    var refreshResult: TokenRefreshResult = TokenRefreshResult(success = false)
    var athleteName: String? = null

    data class UpdateCall(val activityId: Long, val body: Map<String, Any?>)

    override suspend fun fetchActivity(accessToken: String, activityId: Long): StravaActivity? {
        return activities[activityId]
    }

    override suspend fun fetchRecentActivities(accessToken: String, limit: Int): List<StravaSummaryActivity> {
        return summaries.take(limit)
    }

    override suspend fun fetchActivitiesSince(accessToken: String, afterEpochSeconds: Long, limit: Int): List<StravaSummaryActivity> {
        return summaries.take(limit)
    }

    override suspend fun updateActivity(accessToken: String, activityId: Long, body: Map<String, Any?>): UpdateResult {
        updateCalls.add(UpdateCall(activityId, body))
        return updateResult
    }

    override suspend fun fetchAthleteName(accessToken: String): String? {
        return athleteName
    }

    override suspend fun refreshToken(clientId: String, clientSecret: String, refreshToken: String): TokenRefreshResult {
        return refreshResult
    }
}

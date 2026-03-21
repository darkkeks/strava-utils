package stravahooks.strava

interface StravaApi {
    suspend fun fetchActivity(accessToken: String, activityId: Long): StravaActivity?
    suspend fun fetchRecentActivities(accessToken: String, limit: Int): List<StravaSummaryActivity>
    suspend fun fetchActivitiesSince(accessToken: String, afterEpochSeconds: Long, limit: Int): List<StravaSummaryActivity>
    suspend fun updateActivity(accessToken: String, activityId: Long, body: Map<String, Any?>): UpdateResult
    suspend fun fetchAthleteName(accessToken: String): String?
    suspend fun refreshToken(clientId: String, clientSecret: String, refreshToken: String): TokenRefreshResult
}

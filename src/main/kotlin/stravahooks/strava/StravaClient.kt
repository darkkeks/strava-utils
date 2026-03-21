package stravahooks.strava

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.jackson.jackson
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory

data class UpdateResult(
    val success: Boolean,
    val status: String? = null,
    val body: String? = null,
    val error: String? = null
)

data class TokenRefreshResult(
    val success: Boolean,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAt: Long? = null,
    val error: String? = null
)

class StravaClient : StravaApi {
    private val logger = LoggerFactory.getLogger(StravaClient::class.java)
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            jackson()
        }
    }

    override suspend fun fetchActivity(accessToken: String, activityId: Long): StravaActivity? {
        return try {
            httpClient.get("https://www.strava.com/api/v3/activities/$activityId") {
                headers.append(HttpHeaders.Authorization, "Bearer $accessToken")
            }.body()
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun fetchRecentActivities(accessToken: String, limit: Int): List<StravaSummaryActivity> {
        return try {
            val response = httpClient.get("https://www.strava.com/api/v3/athlete/activities") {
                headers.append(HttpHeaders.Authorization, "Bearer $accessToken")
                url {
                    parameters.append("per_page", limit.toString())
                }
            }
            if (!response.status.isSuccess()) {
                val raw = response.bodyAsText().trim()
                val snippet = if (raw.length > 500) raw.take(500) + "…" else raw
                logger.warn("Strava list activities failed: status=${response.status}, body=$snippet")
                return emptyList()
            }
            val activities: List<StravaSummaryActivity> = response.body()
            logger.info("Strava list activities: fetched ${activities.size} (per_page=$limit)")
            activities
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun fetchActivitiesSince(accessToken: String, afterEpochSeconds: Long, limit: Int): List<StravaSummaryActivity> {
        return try {
            httpClient.get("https://www.strava.com/api/v3/athlete/activities") {
                headers.append(HttpHeaders.Authorization, "Bearer $accessToken")
                url {
                    parameters.append("after", afterEpochSeconds.toString())
                    parameters.append("per_page", limit.toString())
                }
            }.body()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun updateActivity(accessToken: String, activityId: Long, body: Map<String, Any?>): UpdateResult {
        return try {
            val response = httpClient.put("https://www.strava.com/api/v3/activities/$activityId") {
                headers.append(HttpHeaders.Authorization, "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            if (!response.status.isSuccess()) {
                val raw = response.bodyAsText().trim()
                val snippet = if (raw.length > 500) raw.take(500) + "…" else raw
                UpdateResult(false, status = response.status.toString(), body = snippet)
            } else {
                UpdateResult(true)
            }
        } catch (e: Exception) {
            UpdateResult(false, error = e.message)
        }
    }

    override suspend fun fetchAthleteName(accessToken: String): String? {
        return try {
            val response: AthleteResponse = httpClient.get("https://www.strava.com/api/v3/athlete") {
                headers.append(HttpHeaders.Authorization, "Bearer $accessToken")
            }.body()
            response.fullName()
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun refreshToken(clientId: String, clientSecret: String, refreshToken: String): TokenRefreshResult {
        return try {
            val response = httpClient.post("https://www.strava.com/api/v3/oauth/token") {
                setBody(
                    mapOf(
                        "client_id" to clientId,
                        "client_secret" to clientSecret,
                        "grant_type" to "refresh_token",
                        "refresh_token" to refreshToken
                    )
                )
                contentType(ContentType.Application.Json)
            }
            if (!response.status.isSuccess()) {
                val raw = response.bodyAsText().trim()
                val snippet = if (raw.length > 500) raw.take(500) + "…" else raw
                return TokenRefreshResult(success = false, error = snippet)
            }
            val token: TokenResponse = response.body()
            TokenRefreshResult(
                success = true,
                accessToken = token.accessToken,
                refreshToken = token.refreshToken,
                expiresAt = token.expiresAt
            )
        } catch (e: Exception) {
            TokenRefreshResult(success = false, error = e.message)
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class AthleteResponse(
    @field:JsonProperty("firstname")
    val firstName: String? = null,
    @field:JsonProperty("lastname")
    val lastName: String? = null
) {
    fun fullName(): String? {
        val first = firstName?.trim().orEmpty()
        val last = lastName?.trim().orEmpty()
        val full = listOf(first, last).filter { it.isNotBlank() }.joinToString(" ")
        return full.ifBlank { null }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class TokenResponse(
    @field:JsonProperty("access_token")
    val accessToken: String? = null,
    @field:JsonProperty("refresh_token")
    val refreshToken: String? = null,
    @field:JsonProperty("expires_at")
    val expiresAt: Long? = null
)

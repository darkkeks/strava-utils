package stravahooks.oauth

import stravahooks.config.StravaHooksConfig
import stravahooks.storage.DataStore
import stravahooks.storage.StravaAccount
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.request.ApplicationRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import io.ktor.serialization.jackson.jackson
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.exceptions.TelegramApiException

class StravaOAuthServer(
    private val config: StravaHooksConfig,
    private val dataStore: DataStore,
    private val oauthStateStore: OAuthStateStore
) {
    private val defaultScopes = "read,activity:read_all,activity:write"
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            jackson()
        }
    }
    private val logger = LoggerFactory.getLogger(StravaOAuthServer::class.java)

    fun start() {
        val host = config.bindHost ?: "0.0.0.0"
        val port = config.bindPort ?: 8080

        embeddedServer(Netty, host = host, port = port) {
            monitor.subscribe(ApplicationStopping) {
                client.close()
            }

            install(CallLogging)
            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    logger.error("Unhandled exception in OAuth callback", cause)
                    call.respondText("Internal server error.")
                }
            }

            routing {
                get("/strava/oauth/callback") {
                    val code = call.request.queryOrNull("code")
                    val state = call.request.queryOrNull("state")
                    val scopeParam = call.request.queryOrNull("scope")

                    if (code.isNullOrBlank() || state.isNullOrBlank()) {
                        logger.warn("OAuth callback missing code or state")
                        call.respondText("Missing code or state.")
                        return@get
                    }

                    val telegramUserId = oauthStateStore.consume(state)
                    if (telegramUserId == null) {
                        logger.warn("OAuth callback state invalid or expired")
                        call.respondText("Invalid or expired state. Please link again from Telegram.")
                        return@get
                    }

                    val token = exchangeCode(code)
                    val mergedToken = if (token.scope.isNullOrBlank() && !scopeParam.isNullOrBlank()) {
                        token.copy(scope = scopeParam)
                    } else {
                        token
                    }
                    if (!mergedToken.isComplete()) {
                        call.respondText("Failed to link Strava account. Please try again.")
                        return@get
                    }
                    persistTokens(telegramUserId, mergedToken)
                    notifyLinked(telegramUserId, mergedToken.scope ?: defaultScopes)
                    call.respondText("Strava account linked. You can return to Telegram.")
                }
            }
        }.start(wait = false)
    }

    private suspend fun exchangeCode(code: String): TokenResponse {
        val clientId = config.stravaClientId
        val clientSecret = config.stravaClientSecret
        require(!clientId.isNullOrBlank()) { "strava_client_id is required" }
        require(!clientSecret.isNullOrBlank()) { "strava_client_secret is required" }

        val response: HttpResponse = client.submitForm(
            url = "https://www.strava.com/api/v3/oauth/token",
            formParameters = Parameters.build {
                append("client_id", clientId)
                append("client_secret", clientSecret)
                append("code", code)
                append("grant_type", "authorization_code")
            }
        )
        val raw = response.bodyAsText().trim()
        if (!response.status.isSuccess()) {
            val snippet = redactTokenBody(raw)
            logger.warn("Strava token exchange failed: status=${response.status}, body=$snippet")
            return TokenResponse()
        }
        return try {
            val token: TokenResponse = jacksonObjectMapper().readValue(raw)
            if (!token.isComplete()) {
                logger.warn(
                    "Strava token exchange returned incomplete payload: status=${response.status}, body=${redactTokenBody(raw)}"
                )
            }
            token
        } catch (e: Exception) {
            logger.warn("Strava token exchange response parse failed: ${e.message}")
            TokenResponse()
        }
    }

    private fun notifyLinked(telegramUserId: Long, scope: String?) {
        val telegramClient = OkHttpTelegramClient(config.telegramBotToken)
        val text = if (scope.isNullOrBlank()) {
            "Strava account linked."
        } else {
            "Strava account linked. Scope: $scope"
        }

        val message = SendMessage.builder()
            .chatId(telegramUserId.toString())
            .text(text)
            .build()

        try {
            telegramClient.execute(message)
        } catch (e: TelegramApiException) {
            logger.warn("Failed to send Telegram notification: ${e.message}")
        }
    }

    private fun persistTokens(telegramUserId: Long, token: TokenResponse) {
        if (!token.isComplete()) {
            logger.error("Token response missing required fields; cannot persist")
            return
        }

        val accessToken = token.accessToken.orEmpty()
        val refreshToken = token.refreshToken.orEmpty()
        val expiresAt = token.expiresAt ?: 0
        val scope = token.scope ?: defaultScopes
        dataStore.upsertUser(telegramUserId) { user ->
            user.copy(
                strava = StravaAccount(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresAt = expiresAt,
                    scope = scope,
                    athleteId = token.athlete?.id,
                    athleteName = token.athlete?.fullName()
                )
            )
        }
    }

    private fun ApplicationRequest.queryOrNull(name: String): String? {
        return queryParameters[name]
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class TokenResponse(
    @field:JsonProperty("token_type")
    val tokenType: String? = null,
    @field:JsonProperty("access_token")
    val accessToken: String? = null,
    @field:JsonProperty("refresh_token")
    val refreshToken: String? = null,
    @field:JsonProperty("expires_at")
    val expiresAt: Long? = null,
    @field:JsonProperty("expires_in")
    val expiresIn: Long? = null,
    @field:JsonProperty("scope")
    val scope: String? = null,
    @field:JsonProperty("athlete")
    val athlete: TokenAthlete? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TokenAthlete(
    @field:JsonProperty("id")
    val id: Long? = null,
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

private fun TokenResponse.isComplete(): Boolean {
    return !accessToken.isNullOrBlank() &&
        !refreshToken.isNullOrBlank() &&
        expiresAt != null
}

private fun redactTokenBody(body: String): String {
    if (body.isBlank()) {
        return body
    }
    val redacted = body
        .replace(Regex("\"access_token\"\\s*:\\s*\"[^\"]*\""), "\"access_token\":\"<redacted>\"")
        .replace(Regex("\"refresh_token\"\\s*:\\s*\"[^\"]*\""), "\"refresh_token\":\"<redacted>\"")
    return if (redacted.length > 500) redacted.take(500) + "…" else redacted
}

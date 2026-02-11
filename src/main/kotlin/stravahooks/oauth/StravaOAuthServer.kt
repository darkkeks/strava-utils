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
import io.ktor.http.Parameters
import io.ktor.serialization.jackson.jackson
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.exceptions.TelegramApiException

class StravaOAuthServer(
    private val config: StravaHooksConfig,
    private val dataStore: DataStore
) {
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

                    if (code.isNullOrBlank() || state.isNullOrBlank()) {
                        logger.warn("OAuth callback missing code or state")
                        call.respondText("Missing code or state.")
                        return@get
                    }

                    val telegramUserId = OAuthStateStore.consume(state)
                    if (telegramUserId == null) {
                        logger.warn("OAuth callback state invalid or expired")
                        call.respondText("Invalid or expired state. Please link again from Telegram.")
                        return@get
                    }

                    val token = exchangeCode(code)
                    persistTokens(telegramUserId, token)
                    notifyLinked(telegramUserId, token.scope)
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

        return client.submitForm(
            url = "https://www.strava.com/api/v3/oauth/token",
            formParameters = Parameters.build {
                append("client_id", clientId)
                append("client_secret", clientSecret)
                append("code", code)
                append("grant_type", "authorization_code")
            }
        ).body()
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
        val accessToken = token.accessToken
        val refreshToken = token.refreshToken
        val expiresAt = token.expiresAt
        val scope = token.scope
        if (accessToken.isNullOrBlank() || refreshToken.isNullOrBlank() || expiresAt == null || scope.isNullOrBlank()) {
            logger.error("Token response missing required fields; cannot persist")
            return
        }

        dataStore.upsertUser(telegramUserId) { user ->
            user.copy(
                strava = StravaAccount(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresAt = expiresAt,
                    scope = scope,
                    athleteId = token.athlete?.id
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
    val id: Long? = null
)

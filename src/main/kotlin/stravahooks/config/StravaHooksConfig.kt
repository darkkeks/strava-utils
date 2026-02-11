package stravahooks.config

data class StravaHooksConfig(
    val telegramBotToken: String,
    val stravaClientId: String? = null,
    val stravaClientSecret: String? = null,
    val baseUrl: String? = null,
    val webhookVerifyToken: String? = null,
    val dataPath: String? = null,
    val polling: Boolean = true,
    val telegramWebhookPath: String? = null,
    val bindHost: String? = null,
    val bindPort: Int? = null
)

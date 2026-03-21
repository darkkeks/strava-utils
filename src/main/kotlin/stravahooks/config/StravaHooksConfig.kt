package stravahooks.config

data class StravaHooksConfig(
    val telegramBotToken: String,
    val stravaClientId: String? = null,
    val stravaClientSecret: String? = null,
    val baseUrl: String? = null,
    val webhookVerifyToken: String? = null,
    val dataPath: String? = null,
    val polling: Boolean = true,
    val pollingIntervalSeconds: Long? = 300,
    val pollingLookbackSeconds: Long? = 3600,
    val telegramWebhookPath: String? = null,
    val bindHost: String? = null,
    val bindPort: Int? = null,
    val maxScriptInstructions: Int? = null,
    val maxPollEditsPerCycle: Int? = null
)

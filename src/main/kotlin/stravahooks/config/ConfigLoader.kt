package stravahooks.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Path
import kotlin.io.path.exists

object ConfigLoader {
    private val mapper = jacksonObjectMapper()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun defaultPath(): Path {
        val override = System.getenv("STRAVAHOOKS_CONFIG")
        return if (override.isNullOrBlank()) {
            Path.of("stravahooks.json")
        } else {
            Path.of(override)
        }
    }

    fun load(path: Path): StravaHooksConfig {
        if (!path.exists()) {
            error("Config file not found: $path")
        }

        val config = mapper.readValue<StravaHooksConfig>(path.toFile())
        require(config.telegramBotToken.isNotBlank()) {
            "telegram_bot_token is required in $path"
        }
        return config
    }

    fun writeStub(path: Path, force: Boolean) {
        if (path.exists() && !force) {
            error("Config file already exists: $path (use --force to overwrite)")
        }

        val stub = """
            {
              "telegram_bot_token": "",
              "strava_client_id": "",
              "strava_client_secret": "",
              "base_url": "http://localhost:8080",
              "webhook_verify_token": "",
              "data_path": "stravahooks.db.json",
              "polling": true,
              "polling_interval_seconds": 300,
              "polling_lookback_seconds": 3600,
              "telegram_webhook_path": "/telegram",
              "bind_host": "0.0.0.0",
              "bind_port": 8080
            }
        """.trimIndent()

        path.toFile().writeText(stub)
    }
}

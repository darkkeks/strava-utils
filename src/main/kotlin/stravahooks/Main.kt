package stravahooks

import stravahooks.config.ConfigLoader
import stravahooks.oauth.OAuthStateStore
import stravahooks.oauth.StravaOAuthServer
import stravahooks.polling.ActivityPoller
import stravahooks.storage.DataStore
import stravahooks.telegram.StravaHooksBot
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication
import java.nio.file.Path

fun main(args: Array<String>) {
    if (args.isEmpty() || args.contains("--help") || args.contains("-h")) {
        printHelp()
        return
    }

    val command = args.first()
    val options = args.drop(1)

    when (command) {
        "run" -> runBot(parseConfigPath(options))
        "init" -> initConfig(options)
        else -> error("Unknown command: $command")
    }
}

private fun runBot(configPath: Path) {
    val config = ConfigLoader.load(configPath)
    val dataPath = config.dataPath ?: error("data_path is required to persist tokens")
    val dataStore = DataStore(Path.of(dataPath))
    val oauthStateStore = OAuthStateStore(dataStore)
    val oauthServer = StravaOAuthServer(config, dataStore, oauthStateStore)
    oauthServer.start()
    ActivityPoller(config, dataStore).start()
    val bot = StravaHooksBot(config.telegramBotToken, config, dataStore, oauthStateStore)

    TelegramBotsLongPollingApplication().use { app ->
        app.registerBot(config.telegramBotToken, bot)
        println("Telegram bot started. Press Ctrl+C to stop.")
        Thread.currentThread().join()
    }
}

private fun initConfig(options: List<String>) {
    val configPath = parseConfigPath(options)
    val force = options.contains("--force")
    ConfigLoader.writeStub(configPath, force)
    println("Wrote config stub to $configPath")
}

private fun parseConfigPath(options: List<String>): Path {
    val index = options.indexOfFirst { it == "--config" || it == "-c" }
    return if (index == -1) {
        ConfigLoader.defaultPath()
    } else {
        val value = options.getOrNull(index + 1)
            ?: error("Missing value for --config")
        Path.of(value)
    }
}

private fun printHelp() {
    println(
        """
        stravahooks
        Usage: gradle run --args="<command> [options]"

        Commands:
          run                  Start the Telegram bot (long polling)
          init                 Write a config stub in the working directory

        Options:
          --config, -c <path>  Config path (default: stravahooks.json or STRAVAHOOKS_CONFIG)
          --force              Overwrite config on init
          --help, -h           Show this help
        """.trimIndent()
    )
}

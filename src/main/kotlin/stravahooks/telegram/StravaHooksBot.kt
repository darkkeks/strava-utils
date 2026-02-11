package stravahooks.telegram

import stravahooks.config.StravaHooksConfig
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import org.telegram.telegrambots.meta.generics.TelegramClient
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import stravahooks.oauth.OAuthStateStore

class StravaHooksBot(
    botToken: String,
    private val config: StravaHooksConfig
) : LongPollingSingleThreadUpdateConsumer {
    private val client: TelegramClient = OkHttpTelegramClient(botToken)
    override fun consume(update: Update) {
        if (!update.hasMessage() || !update.message.hasText()) {
            return
        }

        val message = update.message
        val chatId = message.chatId.toString()
        val text = message.text.trim()
        val (reply, markup) = when {
            text.startsWith("/start") || text.startsWith("/help") -> helpText(message.from.id)
            text.startsWith("/link") -> linkText(message.from.id)
            else -> "StravaHooks is running. Use /help to see what I can do." to null
        }

        val request = SendMessage.builder()
            .chatId(chatId)
            .text(reply)
            .replyMarkup(markup)
            .build()

        try {
            client.execute(request)
        } catch (e: TelegramApiException) {
            println("Failed to send message: ${e.message}")
        }
    }

    private fun helpText(telegramUserId: Long): Pair<String, InlineKeyboardMarkup?> {
        val (linkText, markup) = linkText(telegramUserId)
        val help = """
            StravaHooks bot

            Commands:
            /start - overview
            /help - show this help
            /link - link your Strava account

            $linkText
        """.trimIndent()
        return help to markup
    }

    private fun linkText(telegramUserId: Long?): Pair<String, InlineKeyboardMarkup?> {
        val clientId = config.stravaClientId
        val baseUrl = config.baseUrl
        if (clientId.isNullOrBlank() || baseUrl.isNullOrBlank()) {
            return "Linking is not configured yet. Ask the admin to set strava_client_id and base_url." to null
        }

        val state = OAuthStateStore.issue(telegramUserId)
        val redirectUri = "$baseUrl/strava/oauth/callback"
        val scopes = "read,activity:read_all,activity:write"
        val url = buildAuthorizeUrl(clientId, redirectUri, scopes, state)

        val button = InlineKeyboardButton.builder()
            .text("Link Strava account")
            .url(url)
            .build()

        val markup = InlineKeyboardMarkup.builder()
            .keyboard(listOf(InlineKeyboardRow(listOf(button))))
            .build()
        return "Tap the button to link your Strava account." to markup
    }

    private fun buildAuthorizeUrl(
        clientId: String,
        redirectUri: String,
        scopes: String,
        state: String
    ): String {
        val encodedRedirect = URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
        val encodedScope = URLEncoder.encode(scopes, StandardCharsets.UTF_8)
        val encodedState = URLEncoder.encode(state, StandardCharsets.UTF_8)
        return "https://www.strava.com/oauth/authorize" +
            "?client_id=$clientId" +
            "&response_type=code" +
            "&redirect_uri=$encodedRedirect" +
            "&approval_prompt=auto" +
            "&scope=$encodedScope" +
            "&state=$encodedState"
    }

}

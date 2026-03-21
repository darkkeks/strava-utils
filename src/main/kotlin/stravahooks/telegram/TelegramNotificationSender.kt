package stravahooks.telegram

import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.generics.TelegramClient
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import org.slf4j.LoggerFactory

class TelegramNotificationSender(botToken: String) : NotificationSender {
    private val logger = LoggerFactory.getLogger(TelegramNotificationSender::class.java)
    private val client: TelegramClient = OkHttpTelegramClient(botToken)

    override fun sendNotification(telegramUserId: Long, text: String, parseMode: String?) {
        val message = SendMessage.builder()
            .chatId(telegramUserId.toString())
            .text(text)
            .parseMode(parseMode)
            .build()
        try {
            client.execute(message)
        } catch (e: TelegramApiException) {
            logger.warn("Failed to send notification to $telegramUserId: ${e.message}")
        }
    }
}

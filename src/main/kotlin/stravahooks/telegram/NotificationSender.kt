package stravahooks.telegram

interface NotificationSender {
    fun sendNotification(telegramUserId: Long, text: String, parseMode: String? = null)
}

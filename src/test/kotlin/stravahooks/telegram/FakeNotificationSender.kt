package stravahooks.telegram

class FakeNotificationSender : NotificationSender {
    val notifications = mutableListOf<Notification>()

    data class Notification(val telegramUserId: Long, val text: String, val parseMode: String?)

    override fun sendNotification(telegramUserId: Long, text: String, parseMode: String?) {
        notifications.add(Notification(telegramUserId, text, parseMode))
    }
}

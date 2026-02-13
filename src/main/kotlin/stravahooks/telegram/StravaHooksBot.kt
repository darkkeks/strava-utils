package stravahooks.telegram

import stravahooks.config.StravaHooksConfig
import stravahooks.storage.DataStore
import stravahooks.storage.StravaAccount
import stravahooks.storage.ActionDefinition
import stravahooks.storage.PendingActionEdit
import stravahooks.storage.PendingActionCreate
import stravahooks.storage.PendingApply
import stravahooks.storage.ActivityUpdate
import stravahooks.storage.ApplyLogEntry
import stravahooks.storage.ChangePair
import stravahooks.actions.ActionEngine
import stravahooks.strava.StravaClient
import stravahooks.strava.StravaActivity
import stravahooks.strava.StravaSummaryActivity
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import org.telegram.telegrambots.meta.generics.TelegramClient
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import stravahooks.oauth.OAuthStateStore
import org.telegram.telegrambots.meta.api.methods.ParseMode
import kotlinx.coroutines.runBlocking

class StravaHooksBot(
    botToken: String,
    private val config: StravaHooksConfig,
    private val dataStore: DataStore,
    private val oauthStateStore: OAuthStateStore
) : LongPollingSingleThreadUpdateConsumer {
    private val client: TelegramClient = OkHttpTelegramClient(botToken)
    private val actionEngine = ActionEngine()
    private val stravaClient = StravaClient()
    override fun consume(update: Update) {
        if (update.hasCallbackQuery()) {
            handleCallback(update)
            return
        }
        if (!update.hasMessage() || !update.message.hasText()) {
            return
        }

        val message = update.message
        val chatId = message.chatId.toString()
        val text = message.text.trim()
        val pendingHandled = tryHandlePendingEdit(message.from.id, message.text)
        if (pendingHandled != null) {
            val request = SendMessage.builder()
                .chatId(chatId)
                .text(pendingHandled.first)
                .replyMarkup(pendingHandled.second)
                .parseMode(pendingHandled.third)
                .build()
            try {
                client.execute(request)
            } catch (e: TelegramApiException) {
                println("Failed to send message: ${e.message}")
            }
            return
        }

        val (reply, markup) = when {
            text.startsWith("/start") || text.startsWith("/help") || text.startsWith("/menu") -> mainMenuReply(message.from.id)
            text.startsWith("/status") -> statusReply(message.from.id)
            text.startsWith("/link") -> linkText(message.from.id)
            text.startsWith("/actions") -> actionsReply(message.from.id, text)
            text.startsWith("/apply") -> applyReply(message.from.id, text)
            text.startsWith("/logs") -> logsReply(message.from.id, text) to mainMenuMarkup()
            text.startsWith("/whoami") -> whoamiReply(message.from.id) to null
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

    private fun handleCallback(update: Update) {
        val callback = update.callbackQuery
        val data = callback.data ?: return
        val chatId = callback.message?.chatId?.toString() ?: return
        when (data) {
            "logout" -> {
                dataStore.upsertUser(callback.from.id) { user ->
                    user.copy(strava = null, pendingActionEdit = null, pendingActionCreate = null, pendingApply = null)
                }
                editCallbackMessage(callback, "Logged out. Use /link to connect again.", mainMenuMarkup())
            }
            else -> {
                if (data == "menu") {
                    val (reply, markup) = mainMenuReply(callback.from.id)
                    editCallbackMessage(callback, reply, markup)
                } else if (data == "back_actions") {
                    val (reply, markup) = actionsReply(callback.from.id, "/actions")
                    editCallbackMessage(callback, reply, markup)
                } else if (data == "actions_menu") {
                    val (reply, markup) = actionsReply(callback.from.id, "/actions")
                    editCallbackMessage(callback, reply, markup)
                } else if (data == "apply_menu") {
                    val (reply, markup) = applyReply(callback.from.id, "/apply")
                    editCallbackMessage(callback, reply, markup)
                } else if (data == "status_menu") {
                    val (reply, markup) = statusReply(callback.from.id)
                    editCallbackMessage(callback, reply, markup)
                } else if (data == "logs_menu") {
                    val reply = logsReply(callback.from.id, "/logs")
                    editCallbackMessage(callback, reply, mainMenuMarkup())
                } else if (data == "link_menu") {
                    val (reply, markup) = linkText(callback.from.id)
                    editCallbackMessage(callback, reply, markup)
                } else if (data == "action_create") {
                    beginActionCreate(callback.from.id)
                    editCallbackMessage(callback, "Send the new action name.")
                } else if (data == "action_create_default") {
                    val user = dataStore.getUser(callback.from.id)
                    val name = user?.pendingActionCreate?.name ?: "Action"
                    val action = createAction(callback.from.id, name)
                    dataStore.upsertUser(callback.from.id) { current ->
                        current.copy(pendingActionCreate = null)
                    }
                    val picker = buildActivityPickerForAction(callback.from.id, action.id)
                    editCallbackMessage(callback, picker.first, picker.second)
                } else if (data.startsWith("action_show:")) {
                    val id = data.removePrefix("action_show:")
                    val action = getAction(callback.from.id, id)
                    val text = if (action == null) {
                        "Action not found."
                    } else {
                        val status = if (action.enabled) "enabled" else "disabled"
                        val name = htmlEscape(action.name)
                        val code = htmlEscape(action.code)
                        "Action \"${name}\" ($status)\n<pre><code>$code</code></pre>"
                    }
                    editCallbackMessage(callback, text, actionDetailMarkup(action), ParseMode.HTML)
                } else if (data.startsWith("action_edit:")) {
                    val id = data.removePrefix("action_edit:")
                    val action = getAction(callback.from.id, id)
                    val text = if (action == null) {
                        "Action not found."
                    } else {
                        beginActionEdit(callback.from.id, action.id)
                        "Send the new code for action \"${action.name}\"."
                    }
                    val message = SendMessage.builder()
                        .chatId(chatId)
                        .text(text)
                        .build()
                    try {
                        client.execute(message)
                    } catch (e: TelegramApiException) {
                        println("Failed to send message: ${e.message}")
                    }
                } else if (data.startsWith("action_check:")) {
                    val id = data.removePrefix("action_check:")
                    val picker = buildActivityPickerForAction(callback.from.id, id)
                    editCallbackMessage(callback, picker.first, picker.second)
                } else if (data.startsWith("action_toggle:")) {
                    val id = data.removePrefix("action_toggle:")
                    val toggled = toggleAction(callback.from.id, id)
                    val action = getAction(callback.from.id, id)
                    val text = if (!toggled || action == null) {
                        "Action not found."
                    } else {
                        val status = if (action.enabled) "enabled" else "disabled"
                        "Action ${action.name} is now $status."
                    }
                    editCallbackMessage(callback, text, actionDetailMarkup(action))
                } else if (data.startsWith("action_delete:")) {
                    val id = data.removePrefix("action_delete:")
                    val action = getAction(callback.from.id, id)
                    if (action == null) {
                        editCallbackMessage(callback, "Action not found.", actionDetailMarkup(null))
                    } else {
                        beginActionDelete(callback.from.id, id)
                        val name = htmlEscape(action.name)
                        val code = htmlEscape(action.code)
                        val text = "Delete action \"${name}\"?\n<pre><code>$code</code></pre>"
                        editCallbackMessage(
                            callback,
                            text,
                            actionDeleteMarkup(action.id),
                            ParseMode.HTML
                        )
                    }
                } else if (data.startsWith("action_delete_confirm:")) {
                    val id = data.removePrefix("action_delete_confirm:")
                    val deletedName = deleteAction(callback.from.id, id)
                    if (deletedName == null) {
                        editCallbackMessage(callback, "Action not found.", actionDetailMarkup(null))
                    } else {
                        val (reply, markup) = actionsReply(callback.from.id, "/actions")
                        editCallbackMessage(
                            callback,
                            "Deleted action \"$deletedName\".\n\n$reply",
                            markup
                        )
                    }
                } else if (data.startsWith("action_delete_cancel:")) {
                    val id = data.removePrefix("action_delete_cancel:")
                    clearPendingActionDelete(callback.from.id, id)
                    val action = getAction(callback.from.id, id)
                    val text = if (action == null) {
                        "Action not found."
                    } else {
                        val status = if (action.enabled) "enabled" else "disabled"
                        val name = htmlEscape(action.name)
                        val code = htmlEscape(action.code)
                        "Action \"${name}\" ($status)\n<pre><code>$code</code></pre>"
                    }
                    editCallbackMessage(callback, text, actionDetailMarkup(action), ParseMode.HTML)
                } else if (data.startsWith("apply_confirm:")) {
                    val id = data.removePrefix("apply_confirm:")
                    val text = applyPending(callback.from.id, id)
                    editCallbackMessage(callback, text, mainMenuMarkup())
                } else if (data.startsWith("apply_cancel:")) {
                    clearPendingApply(callback.from.id)
                    editCallbackMessage(callback, "Apply canceled.", mainMenuMarkup())
                } else if (data.startsWith("apply_preview:")) {
                    val id = data.removePrefix("apply_preview:")
                    val preview = previewApply(callback.from.id, id)
                    editCallbackMessage(callback, preview.first, preview.second)
                } else if (data.startsWith("apply_pick:")) {
                    val id = data.removePrefix("apply_pick:")
                    val picker = buildActionPickerSingle(callback.from.id, id)
                    editCallbackMessage(callback, picker.first, picker.second)
                } else if (data.startsWith("apply_preview_action:")) {
                    val parts = data.removePrefix("apply_preview_action:").split(":")
                    if (parts.size == 2) {
                        val activityId = parts[0]
                        val actionId = parts[1]
                        val preview = previewApplySingle(callback.from.id, activityId, actionId)
                        editCallbackMessage(callback, preview.first, preview.second)
                    }
                }
            }
        }

        val ack = AnswerCallbackQuery.builder()
            .callbackQueryId(callback.id)
            .build()
        try {
            client.execute(ack)
        } catch (e: TelegramApiException) {
            println("Failed to answer callback: ${e.message}")
        }
    }

    private fun helpText(telegramUserId: Long): Pair<String, InlineKeyboardMarkup?> {
        val (_, accountStatus) = accountStatus(telegramUserId)
        val (linkText, markup) = linkText(telegramUserId)
        val help = """
            StravaHooks bot

            Commands:
            /start - overview
            /help - show this help
            /status - show account status
            /link - link your Strava account
            /actions - list and manage actions
            /apply - preview/apply actions on a recent activity
            /logs - show recent apply logs
            /whoami - show debug identity

            $accountStatus
            $linkText
        """.trimIndent()
        return help to markup
    }

    private fun mainMenuReply(telegramUserId: Long): Pair<String, InlineKeyboardMarkup?> {
        val (_, status) = accountStatus(telegramUserId)
        return "Main menu\n$status" to mainMenuMarkup()
    }

    private fun mainMenuMarkup(): InlineKeyboardMarkup {
        val rows = listOf(
            InlineKeyboardRow(
                listOf(
                    InlineKeyboardButton.builder().text("Actions").callbackData("actions_menu").build(),
                    InlineKeyboardButton.builder().text("Apply").callbackData("apply_menu").build()
                )
            ),
            InlineKeyboardRow(
                listOf(
                    InlineKeyboardButton.builder().text("Status").callbackData("status_menu").build(),
                    InlineKeyboardButton.builder().text("Logs").callbackData("logs_menu").build()
                )
            ),
            InlineKeyboardRow(
                listOf(
                    InlineKeyboardButton.builder().text("Link / Relink").callbackData("link_menu").build()
                )
            )
        )
        return InlineKeyboardMarkup.builder()
            .keyboard(rows)
            .build()
    }

    private fun editCallbackMessage(
        callback: org.telegram.telegrambots.meta.api.objects.CallbackQuery,
        text: String,
        markup: InlineKeyboardMarkup? = null,
        parseMode: String? = null
    ) {
        val messageId = callback.message?.messageId
        val chatId = callback.message?.chatId?.toString()
        if (messageId == null || chatId == null) {
            val fallback = SendMessage.builder()
                .chatId(callback.from.id.toString())
                .text(text)
                .replyMarkup(markup)
                .parseMode(parseMode)
                .build()
            try {
                client.execute(fallback)
            } catch (e: TelegramApiException) {
                println("Failed to send message: ${e.message}")
            }
            return
        }
        val edit = EditMessageText.builder()
            .chatId(chatId)
            .messageId(messageId)
            .text(text)
            .replyMarkup(markup)
            .parseMode(parseMode)
            .build()
        try {
            client.execute(edit)
        } catch (e: TelegramApiException) {
            println("Failed to edit message: ${e.message}")
        }
    }

    private fun withMainMenu(markup: InlineKeyboardMarkup?): InlineKeyboardMarkup? {
        val rows = markup?.keyboard?.toMutableList() ?: mutableListOf()
        rows.add(
            InlineKeyboardRow(
                listOf(
                    InlineKeyboardButton.builder().text("Main menu").callbackData("menu").build()
                )
            )
        )
        return InlineKeyboardMarkup.builder()
            .keyboard(rows)
            .build()
    }

    private fun statusReply(telegramUserId: Long): Pair<String, InlineKeyboardMarkup?> {
        val user = dataStore.getUser(telegramUserId)
        val account = user?.strava ?: return "Account: not linked." to null
        val refreshedName = refreshAthleteName(telegramUserId, account)
        val label = refreshedName
            ?: account.athleteName
            ?: account.athleteId?.let { "athlete $it" }
            ?: "unknown athlete"
        val expiresAt = Instant.ofEpochSecond(account.expiresAt)
        val expiresText = DateTimeFormatter.ISO_INSTANT.format(expiresAt)
        val scopeSummary = scopeSummary(account.scope)
        val scopeWarnings = scopeWarnings(account.scope)
        val text = """
            Account: linked as $label
            Scope: $scopeSummary
            Token expires: $expiresText
            $scopeWarnings
        """.trimIndent()
        val buttons = mutableListOf<InlineKeyboardButton>()
        if (!hasFullScope(account.scope)) {
            val upgrade = buildUpgradeButton(telegramUserId)
            if (upgrade != null) {
                buttons.add(upgrade)
            }
        }
        buttons.add(
            InlineKeyboardButton.builder()
                .text("Log out")
                .callbackData("logout")
                .build()
        )
        val markup = InlineKeyboardMarkup.builder()
            .keyboard(listOf(InlineKeyboardRow(buttons)))
            .build()
        return text to withMainMenu(markup)
    }

    private fun scopeSummary(scope: String): String {
        val normalized = scope.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return if (normalized.isEmpty()) {
            "unknown"
        } else {
            normalized.joinToString(", ")
        }
    }

    private fun scopeWarnings(scope: String): String {
        val normalized = scope.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (normalized.isEmpty()) {
            return "Scope warning: missing scope info."
        }
        val warnings = mutableListOf<String>()
        if (!normalized.contains("activity:write")) {
            warnings.add("missing activity:write (can’t edit activities)")
        }
        if (!normalized.contains("activity:read_all")) {
            warnings.add("missing activity:read_all (can’t access private activities)")
        }
        if (warnings.isEmpty()) {
            return "Scope: full access for edits."
        }
        return "Scope warning: " + warnings.joinToString("; ")
    }

    private fun hasFullScope(scope: String): Boolean {
        val normalized = scope.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return normalized.containsAll(REQUIRED_SCOPES)
    }

    private fun buildUpgradeButton(telegramUserId: Long): InlineKeyboardButton? {
        val clientId = config.stravaClientId
        val baseUrl = config.baseUrl
        if (clientId.isNullOrBlank() || baseUrl.isNullOrBlank()) {
            return null
        }
        val state = oauthStateStore.issue(telegramUserId)
        val redirectUri = "$baseUrl/strava/oauth/callback"
        val scopes = REQUIRED_SCOPES.joinToString(",")
        val url = buildAuthorizeUrl(clientId, redirectUri, scopes, state)
        return InlineKeyboardButton.builder()
            .text("Upgrade scopes")
            .url(url)
            .build()
    }

    companion object {
        private val REQUIRED_SCOPES = setOf("read", "activity:read_all", "activity:write")
        private const val DEFAULT_ACTION_CODE = """
function action(activity) {
  // TODO: edit activity fields here
}
"""
    }

    private fun actionsReply(telegramUserId: Long, text: String): Pair<String, InlineKeyboardMarkup?> {
        val parts = text.split(" ", limit = 4)
        if (parts.size >= 2 && (parts[1] == "new" || parts[1] == "create")) {
            val name = parts.getOrNull(2)?.trim().orEmpty()
            if (name.isBlank()) {
                return "Usage: /actions new <name>" to null
            }
            val action = createAction(telegramUserId, name)
            return "Created action ${action.name}. It starts disabled." to null
        }
        if (parts.size >= 4 && parts[1] == "code") {
            val id = parts[2].trim()
            val code = parts[3].trim()
            if (id.isBlank() || code.isBlank()) {
                return "Usage: /actions code <id> <javascript>" to null
            }
            val updated = updateActionCode(telegramUserId, id, code)
            return if (updated) {
                "Updated action code."
            } else {
                "Action not found."
            } to null
        }
        if (parts.size >= 3 && parts[1] == "show") {
            val id = parts[2].trim()
            if (id.isBlank()) {
                return "Usage: /actions show <id>" to null
            }
            val action = getAction(telegramUserId, id)
            return if (action == null) {
                "Action not found: $id." to null
            } else {
                val status = if (action.enabled) "enabled" else "disabled"
                val body = """
                    Action ${action.name} (id: ${action.id}, $status)
                    ---
                    ${action.code}
                """.trimIndent()
                body to null
            }
        }
        val user = dataStore.getUser(telegramUserId)
        val actions = user?.actions.orEmpty().sortedBy { it.order }
        if (actions.isEmpty()) {
            return "No actions yet. Create one with: /actions new <name>" to null
        }
        val list = actions.joinToString("\n") { action ->
            val status = if (action.enabled) "enabled" else "disabled"
            "${action.order}. ${action.name} (${status})"
        }
        val rows = actions.map { action ->
            InlineKeyboardRow(
                listOf(
                    InlineKeyboardButton.builder()
                        .text("Show: ${action.name}")
                        .callbackData("action_show:${action.id}")
                        .build()
                )
            )
        }
        val extra = InlineKeyboardRow(
            listOf(
                InlineKeyboardButton.builder().text("Create action").callbackData("action_create").build(),
                InlineKeyboardButton.builder().text("Main menu").callbackData("menu").build()
            )
        )
        val markup = InlineKeyboardMarkup.builder()
            .keyboard(rows.toMutableList().apply { add(extra) })
            .build()
        return "Actions:\n$list" to markup
    }

    private fun whoamiReply(telegramUserId: Long): String {
        val user = dataStore.getUser(telegramUserId)
        val actionsCount = user?.actions?.size ?: 0
        val linked = if (user?.strava == null) "no" else "yes"
        return "Telegram ID: $telegramUserId\nLinked: $linked\nActions: $actionsCount"
    }

    private fun logsReply(telegramUserId: Long, text: String): String {
        val parts = text.split(" ", limit = 2)
        val limit = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 20) ?: 5
        val logs = dataStore.getApplyLogs(telegramUserId, limit)
        if (logs.isEmpty()) {
            return "No logs yet."
        }
        return logs.joinToString("\n\n") { entry ->
            val actions = entry.actionNames.joinToString(", ").ifBlank { "none" }
            val changes = entry.changes.entries.joinToString("; ") { (key, value) ->
                "$key: ${value.before} -> ${value.after}"
            }
            val logsText = entry.logs.take(5).joinToString("; ").ifBlank { "none" }
            """
            [${entry.mode}] activity ${activityUrl(entry.activityId)} • ${entry.result}
            actions: $actions
            changes: ${changes.ifBlank { "none" }}
            logs: $logsText
            """.trimIndent()
        }
    }

    private fun applyReply(telegramUserId: Long, text: String): Pair<String, InlineKeyboardMarkup?> {
        val user = dataStore.getUser(telegramUserId)
            ?: return "Account not linked yet." to null
        val account = refreshAccountIfNeeded(user)
            ?: return "Account not linked yet." to null

        val actions = user.actions.sortedBy { it.order }
        if (actions.isEmpty()) {
            return "No actions yet. Create one first." to null
        }

        val recent = stravaClient.fetchRecentActivities(account.accessToken, 5)
        if (recent.isEmpty()) {
            return "No recent activities found." to null
        }
        val rows = recent.filter { it.id != null }.map { activity ->
            val distanceText = formatDistanceKm(activity.distance)
            val timeText = formatDuration(activity.movingTime)
            val dateText = activity.startDate?.take(10) ?: "unknown date"
            InlineKeyboardRow(
                listOf(
                    InlineKeyboardButton.builder()
                        .text("Choose: ${activity.name ?: "Untitled"} • $distanceText • $timeText • $dateText")
                        .callbackData("apply_pick:${activity.id}")
                        .build()
                )
            )
        }
        if (rows.isEmpty()) {
            return "No recent activities found." to null
        }
        val menuRow = InlineKeyboardRow(
            listOf(
                InlineKeyboardButton.builder().text("Main menu").callbackData("menu").build()
            )
        )
        val markup = InlineKeyboardMarkup.builder()
            .keyboard(rows.toMutableList().apply { add(menuRow) })
            .build()
        return "Pick an activity to configure actions:" to markup
    }

    private fun applyPending(telegramUserId: Long, activityIdText: String): String {
        val user = dataStore.getUser(telegramUserId) ?: return "Account not linked yet."
        val account = refreshAccountIfNeeded(user) ?: return "Account not linked yet."
        val scopeSet = account.scope.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (!scopeSet.contains("activity:write")) {
            return "Missing activity:write scope. Use /status to upgrade."
        }
        val pending = user.pendingApply ?: return "No pending apply."
        if (pending.activityId.toString() != activityIdText) {
            return "Pending activity does not match."
        }
        val body = actionEngine.buildUpdateBody(pending.update)
        val result = stravaClient.updateActivity(account.accessToken, pending.activityId, body)
        clearPendingApply(telegramUserId)
        if (!result.success) {
            val detail = result.status?.let { " ($it)" } ?: ""
            println("Strava update failed$detail: ${result.body ?: result.error}")
        }
        val actions = user.actions.filter { pending.actionIds.contains(it.id) }.sortedBy { it.order }
        writeApplyLog(
            telegramUserId = telegramUserId,
            activityId = pending.activityId,
            actions = actions,
            mode = "apply",
            summary = pending.summary,
            changes = actionEngine.toChangePairsFromSummary(pending.summary),
            logs = emptyList(),
            result = if (result.success) "applied" else "failed",
            error = result.error ?: result.body
        )
        return if (result.success) {
            "Applied changes to activity ${pending.activityId}."
        } else {
            "Failed to apply changes to activity ${pending.activityId}."
        }
    }

    private fun clearPendingApply(telegramUserId: Long) {
        dataStore.upsertUser(telegramUserId) { current ->
            current.copy(pendingApply = null)
        }
    }

    private fun buildActionPickerSingle(
        telegramUserId: Long,
        activityIdText: String
    ): Pair<String, InlineKeyboardMarkup?> {
        val activityId = activityIdText.toLongOrNull()
            ?: return "Invalid activity id: $activityIdText" to null
        val user = dataStore.getUser(telegramUserId)
            ?: return "Account not linked yet." to null
        val actions = user.actions.sortedBy { it.order }
        if (actions.isEmpty()) {
            return "No actions yet. Create one first." to null
        }

        val rows = actions.map { action ->
            val state = if (action.enabled) "" else " (disabled)"
            InlineKeyboardRow(
                listOf(
                    InlineKeyboardButton.builder()
                        .text("Use: ${action.name}$state")
                        .callbackData("apply_preview_action:$activityId:${action.id}")
                        .build()
                )
            )
        }.toMutableList()
        rows.add(
            InlineKeyboardRow(
                listOf(
                    InlineKeyboardButton.builder()
                        .text("Back")
                        .callbackData("apply_menu")
                        .build()
                )
            )
        )

        val markup = InlineKeyboardMarkup.builder()
            .keyboard(rows)
            .build()
        return "Pick one action to run on activity ${activityUrl(activityId)}:" to markup
    }

    private fun buildActivityPickerForAction(
        telegramUserId: Long,
        actionId: String
    ): Pair<String, InlineKeyboardMarkup?> {
        val user = dataStore.getUser(telegramUserId)
            ?: return "Account not linked yet." to null
        val account = refreshAccountIfNeeded(user)
            ?: return "Account not linked yet." to null
        val recent = stravaClient.fetchRecentActivities(account.accessToken, 5)
        if (recent.isEmpty()) {
            return "No recent activities found." to null
        }
        val rows = recent.filter { it.id != null }.map { activity ->
            InlineKeyboardRow(
                listOf(
                    InlineKeyboardButton.builder()
                        .text("Preview on: ${activity.name ?: "Untitled"}")
                        .callbackData("apply_preview_action:${activity.id}:$actionId")
                        .build()
                )
            )
        }.toMutableList()
        rows.add(
            InlineKeyboardRow(
                listOf(
                    InlineKeyboardButton.builder().text("Back").callbackData("actions_menu").build()
                )
            )
        )
        val markup = InlineKeyboardMarkup.builder()
            .keyboard(rows)
            .build()
        return "Pick an activity to preview this action:" to markup
    }

    private fun previewApplySingle(
        telegramUserId: Long,
        activityIdText: String,
        actionId: String
    ): Pair<String, InlineKeyboardMarkup?> {
        val activityId = activityIdText.toLongOrNull()
            ?: return "Invalid activity id: $activityIdText" to null
        val user = dataStore.getUser(telegramUserId)
            ?: return "Account not linked yet." to null
        val account = refreshAccountIfNeeded(user)
            ?: return "Account not linked yet." to null
        val action = user.actions.firstOrNull { it.id == actionId }
            ?: return "Action not found." to null

        val activity = stravaClient.fetchActivity(account.accessToken, activityId)
            ?: return "Failed to fetch activity $activityId." to null

        val normalized = actionEngine.normalizeActivity(activity)
        val before = actionEngine.snapshotWritable(normalized)
        val run = actionEngine.runActions(listOf(action), normalized)
        if (run.errors.isNotEmpty()) {
            writeApplyLog(
                telegramUserId = telegramUserId,
                activityId = activityId,
                actions = listOf(action),
                mode = "preview",
                summary = "Action error",
                changes = emptyMap(),
                logs = run.logs,
                result = "error",
                error = run.errors.joinToString("; ")
            )
            return "Action error:\n" + run.errors.joinToString("\n") to null
        }
        val changes = actionEngine.diffWritable(before, normalized)
        if (changes.isEmpty()) {
            writeApplyLog(
                telegramUserId = telegramUserId,
                activityId = activityId,
                actions = listOf(action),
                mode = "preview",
                summary = "No changes",
                changes = emptyMap(),
                logs = run.logs,
                result = "no_changes",
                error = null
            )
            return "No changes proposed for activity $activityId." to null
        }

        val summary = buildChangeSummary(changes)
        val update = actionEngine.buildUpdate(changes)
        val pending = PendingApply(
            activityId = activityId,
            actionIds = listOf(action.id),
            update = update,
            summary = summary,
            createdAt = Instant.now().epochSecond
        )
        dataStore.upsertUser(telegramUserId) { current ->
            current.copy(pendingApply = pending)
        }

        val confirm = InlineKeyboardButton.builder()
            .text("Apply changes")
            .callbackData("apply_confirm:$activityId")
            .build()
        val cancel = InlineKeyboardButton.builder()
            .text("Cancel")
            .callbackData("apply_cancel:$activityId")
            .build()
        val back = InlineKeyboardButton.builder()
            .text("Back")
            .callbackData("apply_pick:$activityId")
            .build()
        val markup = InlineKeyboardMarkup.builder()
            .keyboard(listOf(InlineKeyboardRow(listOf(confirm, cancel, back))))
            .build()
        val header = activityHeader(activity)
        val logText = if (run.logs.isEmpty()) "" else "\nLogs:\n" + run.logs.joinToString("\n")
        val textReply = """
            $header
            Action: ${action.name}
            Changes:
            $summary$logText
        """.trimIndent()
        writeApplyLog(
            telegramUserId = telegramUserId,
            activityId = activityId,
            actions = listOf(action),
            mode = "preview",
            summary = summary,
            changes = actionEngine.toChangePairs(changes),
            logs = run.logs,
            result = "preview_ready",
            error = null
        )
        return textReply to markup
    }

    private fun previewApply(telegramUserId: Long, activityIdText: String): Pair<String, InlineKeyboardMarkup?> {
        val activityId = activityIdText.toLongOrNull()
            ?: return "Invalid activity id: $activityIdText" to null
        val user = dataStore.getUser(telegramUserId)
            ?: return "Account not linked yet." to null
        val account = refreshAccountIfNeeded(user)
            ?: return "Account not linked yet." to null

        val actions = user.actions.filter { it.enabled }.sortedBy { it.order }
        if (actions.isEmpty()) {
            return "No enabled actions. Enable an action first." to null
        }

        val activity = stravaClient.fetchActivity(account.accessToken, activityId)
            ?: return "Failed to fetch activity $activityId." to null

        val normalized = actionEngine.normalizeActivity(activity)
        val before = actionEngine.snapshotWritable(normalized)
        val run = actionEngine.runActions(actions, normalized)
        if (run.errors.isNotEmpty()) {
            writeApplyLog(
                telegramUserId = telegramUserId,
                activityId = activityId,
                actions = actions,
                mode = "preview",
                summary = "Action error",
                changes = emptyMap(),
                logs = run.logs,
                result = "error",
                error = run.errors.joinToString("; ")
            )
            return "Action error:\n" + run.errors.joinToString("\n") to null
        }
        val changes = actionEngine.diffWritable(before, normalized)
        if (changes.isEmpty()) {
            writeApplyLog(
                telegramUserId = telegramUserId,
                activityId = activityId,
                actions = actions,
                mode = "preview",
                summary = "No changes",
                changes = emptyMap(),
                logs = run.logs,
                result = "no_changes",
                error = null
            )
            return "No changes proposed for activity $activityId." to null
        }

        val summary = buildChangeSummary(changes)
        val update = actionEngine.buildUpdate(changes)
        val pending = PendingApply(
            activityId = activityId,
            actionIds = actions.map { it.id },
            update = update,
            summary = summary,
            createdAt = Instant.now().epochSecond
        )
        dataStore.upsertUser(telegramUserId) { current ->
            current.copy(pendingApply = pending)
        }

        val confirm = InlineKeyboardButton.builder()
            .text("Apply changes")
            .callbackData("apply_confirm:$activityId")
            .build()
        val cancel = InlineKeyboardButton.builder()
            .text("Cancel")
            .callbackData("apply_cancel:$activityId")
            .build()
        val back = InlineKeyboardButton.builder()
            .text("Back")
            .callbackData("apply_pick:$activityId")
            .build()
        val markup = InlineKeyboardMarkup.builder()
            .keyboard(listOf(InlineKeyboardRow(listOf(confirm, cancel, back))))
            .build()
        val header = activityHeader(activity)
        val logText = if (run.logs.isEmpty()) "" else "\nLogs:\n" + run.logs.joinToString("\n")
        val textReply = """
            $header
            Changes:
            $summary$logText
        """.trimIndent()
        writeApplyLog(
            telegramUserId = telegramUserId,
            activityId = activityId,
            actions = actions,
            mode = "preview",
            summary = summary,
            changes = actionEngine.toChangePairs(changes),
            logs = run.logs,
            result = "preview_ready",
            error = null
        )
        return textReply to markup
    }

    private fun buildChangeSummary(changes: Map<String, Pair<Any?, Any?>>): String {
        return changes.entries.joinToString("\n") { (key, value) ->
            val before = value.first?.toString() ?: "null"
            val after = value.second?.toString() ?: "null"
            "$key: $before -> $after"
        }
    }

    private fun activityHeader(activity: StravaActivity): String {
        val name = activity.name ?: "Untitled"
        val dateText = activity.startDate?.take(10) ?: "unknown date"
        val distance = formatDistanceKm(activity.distance)
        val time = formatDuration(activity.movingTime)
        val url = activity.id?.let { activityUrl(it) } ?: "unknown"
        return "Activity: $name • $distance • $time • $dateText • $url"
    }

    private fun activityUrl(activityId: Long): String {
        return "https://www.strava.com/activities/$activityId"
    }

    private fun writeApplyLog(
        telegramUserId: Long,
        activityId: Long,
        actions: List<ActionDefinition>,
        mode: String,
        summary: String,
        changes: Map<String, ChangePair>,
        logs: List<String>,
        result: String,
        error: String?
    ) {
        dataStore.appendApplyLog(
            ApplyLogEntry(
                timestamp = Instant.now().epochSecond,
                telegramUserId = telegramUserId,
                activityId = activityId,
                actionIds = actions.map { it.id },
                actionNames = actions.map { it.name },
                mode = mode,
                summary = summary,
                changes = changes,
                logs = logs,
                result = result,
                error = error
            )
        )
    }

    private fun formatDistanceKm(distanceMeters: Double?): String {
        if (distanceMeters == null) {
            return "?"
        }
        val km = distanceMeters / 1000.0
        return String.format("%.1f km", km)
    }

    private fun formatDuration(seconds: Int?): String {
        if (seconds == null) {
            return "?"
        }
        val total = seconds.coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format("%d:%02d", minutes, secs)
        }
    }

    private fun htmlEscape(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun createAction(telegramUserId: Long, name: String): ActionDefinition {
        val now = Instant.now().epochSecond
        val id = UUID.randomUUID().toString().substring(0, 8)
        val action = ActionDefinition(
            id = id,
            name = name,
            code = DEFAULT_ACTION_CODE,
            enabled = false,
            order = 0,
            createdAt = now,
            updatedAt = now
        )
        val updatedUser = dataStore.upsertUser(telegramUserId) { user ->
            val nextOrder = (user.actions.maxOfOrNull { it.order } ?: 0) + 1
            val updated = action.copy(order = nextOrder)
            user.copy(actions = user.actions + updated)
        }
        return updatedUser.actions.last()
    }

    private fun updateActionCode(telegramUserId: Long, id: String, code: String): Boolean {
        val now = Instant.now().epochSecond
        var found = false
        dataStore.upsertUser(telegramUserId) { user ->
            val updated = user.actions.map { action ->
                if (action.id == id) {
                    found = true
                    action.copy(code = code, updatedAt = now)
                } else {
                    action
                }
            }
            user.copy(actions = updated)
        }
        return found
    }

    private fun getAction(telegramUserId: Long, id: String): ActionDefinition? {
        val user = dataStore.getUser(telegramUserId) ?: return null
        return user.actions.firstOrNull { it.id == id }
    }

    private fun toggleAction(telegramUserId: Long, id: String): Boolean {
        val now = Instant.now().epochSecond
        var found = false
        dataStore.upsertUser(telegramUserId) { user ->
            val updated = user.actions.map { action ->
                if (action.id == id) {
                    found = true
                    action.copy(enabled = !action.enabled, updatedAt = now)
                } else {
                    action
                }
            }
            user.copy(actions = updated)
        }
        return found
    }

    private fun deleteAction(telegramUserId: Long, id: String): String? {
        var deletedName: String? = null
        dataStore.upsertUser(telegramUserId) { user ->
            val remaining = user.actions.filterNot { action ->
                val match = action.id == id
                if (match) {
                    deletedName = action.name
                }
                match
            }
            if (remaining.size == user.actions.size) {
                return@upsertUser user
            }
            val clearedEdit = user.pendingActionEdit?.takeIf { it.actionId != id }
            val clearedDelete = user.pendingActionDelete?.takeIf { it.actionId != id }
            val clearedApply = user.pendingApply?.takeIf { !it.actionIds.contains(id) }
            user.copy(
                actions = remaining,
                pendingActionEdit = clearedEdit,
                pendingActionDelete = clearedDelete,
                pendingApply = clearedApply
            )
        }
        return deletedName
    }

    private fun actionDetailMarkup(action: ActionDefinition?): InlineKeyboardMarkup? {
        if (action == null) {
            return null
        }
        val toggleText = if (action.enabled) "Disable" else "Enable"
        val row = InlineKeyboardRow(
            listOf(
                InlineKeyboardButton.builder()
                    .text("Edit code")
                    .callbackData("action_edit:${action.id}")
                    .build(),
                InlineKeyboardButton.builder()
                    .text(toggleText)
                    .callbackData("action_toggle:${action.id}")
                    .build(),
                InlineKeyboardButton.builder()
                    .text("Check on Activity")
                    .callbackData("action_check:${action.id}")
                    .build()
            )
        )
        val deleteRow = InlineKeyboardRow(
            listOf(
                InlineKeyboardButton.builder()
                    .text("Delete")
                    .callbackData("action_delete:${action.id}")
                    .build()
            )
        )
        val menuRow = InlineKeyboardRow(
            listOf(
                InlineKeyboardButton.builder()
                    .text("Back")
                    .callbackData("back_actions")
                    .build()
            )
        )
        return InlineKeyboardMarkup.builder()
            .keyboard(listOf(row, deleteRow, menuRow))
            .build()
    }

    private fun actionDeleteMarkup(actionId: String): InlineKeyboardMarkup {
        val row = InlineKeyboardRow(
            listOf(
                InlineKeyboardButton.builder()
                    .text("Delete")
                    .callbackData("action_delete_confirm:$actionId")
                    .build(),
                InlineKeyboardButton.builder()
                    .text("Cancel")
                    .callbackData("action_delete_cancel:$actionId")
                    .build()
            )
        )
        return InlineKeyboardMarkup.builder()
            .keyboard(listOf(row))
            .build()
    }

    private fun beginActionEdit(telegramUserId: Long, actionId: String) {
        dataStore.upsertUser(telegramUserId) { user ->
            user.copy(
                pendingActionEdit = PendingActionEdit(actionId, Instant.now().epochSecond),
                pendingActionDelete = null,
                pendingActionCreate = null
            )
        }
    }

    private fun beginActionDelete(telegramUserId: Long, actionId: String) {
        dataStore.upsertUser(telegramUserId) { user ->
            user.copy(
                pendingActionDelete = PendingActionDelete(actionId, Instant.now().epochSecond),
                pendingActionEdit = null,
                pendingActionCreate = null
            )
        }
    }

    private fun clearPendingActionDelete(telegramUserId: Long, actionId: String) {
        dataStore.upsertUser(telegramUserId) { user ->
            if (user.pendingActionDelete?.actionId != actionId) {
                user
            } else {
                user.copy(pendingActionDelete = null)
            }
        }
    }

    private fun beginActionCreate(telegramUserId: Long) {
        dataStore.upsertUser(telegramUserId) { user ->
            user.copy(
                pendingActionCreate = PendingActionCreate(Instant.now().epochSecond, "name"),
                pendingActionDelete = null,
                pendingActionEdit = null
            )
        }
    }

    private fun tryHandlePendingEdit(
        telegramUserId: Long,
        messageText: String
    ): Triple<String, InlineKeyboardMarkup?, String?>? {
        val user = dataStore.getUser(telegramUserId) ?: return null
        val code = messageText.trim()
        if (code.isBlank() || code.startsWith("/")) {
            return null
        }
        val pendingEdit = user.pendingActionEdit
        if (pendingEdit != null) {
            val updated = updateActionCode(telegramUserId, pendingEdit.actionId, code)
            val name = getAction(telegramUserId, pendingEdit.actionId)?.name ?: "action"
            dataStore.upsertUser(telegramUserId) { current ->
                current.copy(pendingActionEdit = null)
            }
            return if (updated) {
                Triple("Updated code for ${name}.", mainMenuMarkup(), null)
            } else {
                Triple("Action not found.", mainMenuMarkup(), null)
            }
        }
        val pendingCreate = user.pendingActionCreate
        if (pendingCreate != null) {
            if (pendingCreate.stage == "name") {
                val name = code
                dataStore.upsertUser(telegramUserId) { current ->
                    current.copy(pendingActionCreate = PendingActionCreate(Instant.now().epochSecond, "code", name))
                }
                return Triple(
                    "Send the action code now (or keep default). Example:\n\n$DEFAULT_ACTION_CODE",
                    InlineKeyboardMarkup.builder()
                        .keyboard(
                            listOf(
                                InlineKeyboardRow(
                                    listOf(
                                        InlineKeyboardButton.builder()
                                            .text("Use default code")
                                            .callbackData("action_create_default")
                                            .build()
                                    )
                                )
                            )
                        )
                        .build(),
                    null
                )
            }
            if (pendingCreate.stage == "code") {
                val action = createAction(telegramUserId, pendingCreate.name ?: "Action")
                updateActionCode(telegramUserId, action.id, code)
                dataStore.upsertUser(telegramUserId) { current ->
                    current.copy(pendingActionCreate = null)
                }
                val picker = buildActivityPickerForAction(telegramUserId, action.id)
                return Triple(picker.first, picker.second, "HTML")
            }
        }
        return null
    }

    private fun linkText(telegramUserId: Long?): Pair<String, InlineKeyboardMarkup?> {
        val clientId = config.stravaClientId
        val baseUrl = config.baseUrl
        if (clientId.isNullOrBlank() || baseUrl.isNullOrBlank()) {
            return "Linking is not configured yet. Ask the admin to set strava_client_id and base_url." to null
        }

        val state = oauthStateStore.issue(telegramUserId)
        val redirectUri = "$baseUrl/strava/oauth/callback"
        val scopes = "read,activity:read_all,activity:write"
        val url = buildAuthorizeUrl(clientId, redirectUri, scopes, state)

        val (linked, status) = accountStatus(telegramUserId)
        val button = InlineKeyboardButton.builder()
            .text("Link Strava account")
            .url(url)
            .build()

        val markup = InlineKeyboardMarkup.builder()
            .keyboard(listOf(InlineKeyboardRow(listOf(button))))
            .build()
        val text = if (linked) {
            "$status\nYou can relink below if needed."
        } else {
            "Tap the button to link your Strava account."
        }
        return text to markup
    }

    private fun accountStatus(telegramUserId: Long?): Pair<Boolean, String> {
        if (telegramUserId == null) {
            return false to "Account: unknown."
        }
        val user = dataStore.getUser(telegramUserId)
        val account = user?.strava
        if (account == null) {
            return false to "Account: not linked."
        }
        val refreshedName = refreshAthleteName(telegramUserId, account)
        val label = account.athleteName
            ?: refreshedName
            ?: account.athleteId?.let { "athlete $it" }
            ?: "unknown athlete"
        return true to "Account: linked as $label."
    }

    private fun refreshAthleteName(telegramUserId: Long, account: StravaAccount): String? {
        return runBlocking {
            val refreshed = refreshAccountIfNeeded(dataStore.getUser(telegramUserId) ?: return@runBlocking null)
                ?: return@runBlocking null
            val fetched = stravaClient.fetchAthleteName(refreshed.accessToken) ?: return@runBlocking null
            if (fetched != account.athleteName) {
                dataStore.upsertUser(telegramUserId) { user ->
                    val current = user.strava ?: return@upsertUser user
                    user.copy(strava = current.copy(athleteName = fetched))
                }
            }
            fetched
        }
    }

    private fun refreshAccountIfNeeded(user: stravahooks.storage.StoredUser): StravaAccount? {
        val account = user.strava ?: return null
        val now = Instant.now().epochSecond
        if (account.expiresAt > now + 60) {
            return account
        }
        val clientId = config.stravaClientId
        val clientSecret = config.stravaClientSecret
        if (clientId.isNullOrBlank() || clientSecret.isNullOrBlank()) {
            return account
        }
        val refreshed = stravaClient.refreshToken(clientId, clientSecret, account.refreshToken)
        if (!refreshed.success || refreshed.accessToken.isNullOrBlank() || refreshed.refreshToken.isNullOrBlank() || refreshed.expiresAt == null) {
            return account
        }
        val updated = account.copy(
            accessToken = refreshed.accessToken,
            refreshToken = refreshed.refreshToken,
            expiresAt = refreshed.expiresAt
        )
        dataStore.upsertUser(user.telegramUserId) { existing ->
            existing.copy(strava = updated)
        }
        return updated
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

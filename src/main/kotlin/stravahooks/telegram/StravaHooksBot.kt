package stravahooks.telegram

import stravahooks.config.StravaHooksConfig
import stravahooks.storage.DataStore
import stravahooks.storage.ActionDefinition
import stravahooks.storage.PendingActionCreate
import stravahooks.actions.ActionEngine
import stravahooks.actions.activityUrl
import stravahooks.strava.REQUIRED_STRAVA_SCOPES
import stravahooks.strava.StravaApi
import stravahooks.strava.StravaClient
import stravahooks.strava.StravaActivity
import stravahooks.strava.ScopeChecker
import kotlinx.coroutines.runBlocking
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
import java.time.Instant
import java.time.format.DateTimeFormatter
import stravahooks.oauth.OAuthStateStore
import org.telegram.telegrambots.meta.api.methods.ParseMode
import stravahooks.storage.PendingActionDelete
import org.slf4j.LoggerFactory

class StravaHooksBot(
    botToken: String,
    private val config: StravaHooksConfig,
    private val dataStore: DataStore,
    private val oauthStateStore: OAuthStateStore,
    private val stravaApi: StravaApi = StravaClient(),
    private val actionEngine: ActionEngine = ActionEngine()
) : LongPollingSingleThreadUpdateConsumer {
    private val logger = LoggerFactory.getLogger(StravaHooksBot::class.java)
    private val client: TelegramClient = OkHttpTelegramClient(botToken)
    private val botActions = BotActions(config, dataStore, actionEngine, stravaApi, oauthStateStore)
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
        val pendingResult = botActions.tryHandlePendingEdit(message.from.id, message.text)
        if (pendingResult != null) {
            val (pendingText, pendingMarkup, pendingParseMode) = formatPendingEditResult(pendingResult, message.from.id)
            val request = SendMessage.builder()
                .chatId(chatId)
                .text(pendingText)
                .replyMarkup(pendingMarkup)
                .parseMode(pendingParseMode)
                .build()
            try {
                client.execute(request)
            } catch (e: TelegramApiException) {
                logger.warn("Failed to send message", e)
            }
            return
        }

        val (reply, markup) = when {
            text.startsWith("/start") -> startReply(message.from.id)
            text.startsWith("/help") -> helpText(message.from.id)
            text.startsWith("/menu") -> mainMenuReply(message.from.id)
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
            logger.warn("Failed to send message", e)
        }
    }

    private fun handleCallback(update: Update) {
        val callback = update.callbackQuery
        val data = callback.data ?: return
        val chatId = callback.message?.chatId?.toString() ?: return
        when {
            data == "logout" -> handleLogout(callback)
            data == "menu" -> handleMenu(callback)
            data == "back_actions" || data == "actions_menu" -> handleActionsMenu(callback)
            data == "apply_menu" -> handleApplyMenu(callback)
            data == "status_menu" -> handleStatusMenu(callback)
            data == "logs_menu" -> handleLogsMenu(callback)
            data == "link_menu" -> handleLinkMenu(callback)
            data == "action_create" -> handleActionCreate(callback)
            data == "action_create_scratch" -> handleActionCreateScratch(callback)
            data == "action_create_default" -> handleActionCreateDefault(callback)
            data == "action_template_list" -> handleActionTemplateList(callback)
            data.startsWith("action_from_template:") -> handleActionFromTemplate(callback, data.removePrefix("action_from_template:"))
            data.startsWith("action_show:") -> handleActionShow(callback, data.removePrefix("action_show:"))
            data.startsWith("action_edit:") -> handleActionEdit(callback, chatId, data.removePrefix("action_edit:"))
            data.startsWith("action_edit_desc:") -> handleActionEditDesc(callback, chatId, data.removePrefix("action_edit_desc:"))
            data.startsWith("action_check:") -> handleActionCheck(callback, data.removePrefix("action_check:"))
            data.startsWith("action_toggle:") -> handleActionToggle(callback, data.removePrefix("action_toggle:"))
            data.startsWith("action_delete:") -> handleActionDelete(callback, data.removePrefix("action_delete:"))
            data.startsWith("action_delete_confirm:") -> handleActionDeleteConfirm(callback, data.removePrefix("action_delete_confirm:"))
            data.startsWith("action_delete_cancel:") -> handleActionDeleteCancel(callback, data.removePrefix("action_delete_cancel:"))
            data.startsWith("apply_confirm:") -> handleApplyConfirm(callback, data.removePrefix("apply_confirm:"))
            data.startsWith("apply_cancel:") -> handleApplyCancel(callback)
            data.startsWith("apply_preview:") -> handleApplyPreview(callback, data.removePrefix("apply_preview:"))
            data.startsWith("apply_pick:") -> handleApplyPick(callback, data.removePrefix("apply_pick:"))
            data.startsWith("apply_preview_action:") -> handleApplyPreviewAction(callback, data.removePrefix("apply_preview_action:"))
        }

        val ack = AnswerCallbackQuery.builder()
            .callbackQueryId(callback.id)
            .build()
        try {
            client.execute(ack)
        } catch (e: TelegramApiException) {
            logger.warn("Failed to answer callback", e)
        }
    }

    private fun handleLogout(callback: org.telegram.telegrambots.meta.api.objects.CallbackQuery) {
        dataStore.upsertUser(callback.from.id) { user ->
            user.copy(strava = null, pendingActionEdit = null, pendingActionCreate = null, pendingApply = null, pendingActionDelete = null)
        }
        editCallbackMessage(callback, "Logged out. Use /link to connect again.", mainMenuMarkup())
    }

    private fun handleMenu(callback: org.telegram.telegrambots.meta.api.objects.CallbackQuery) {
        val (reply, markup) = mainMenuReply(callback.from.id)
        editCallbackMessage(callback, reply, markup)
    }

    private fun handleActionsMenu(callback: org.telegram.telegrambots.meta.api.objects.CallbackQuery) {
        val (reply, markup) = actionsReply(callback.from.id, "/actions")
        editCallbackMessage(callback, reply, markup)
    }

    private fun handleApplyMenu(callback: org.telegram.telegrambots.meta.api.objects.CallbackQuery) {
        val (reply, markup) = applyReply(callback.from.id, "/apply")
        editCallbackMessage(callback, reply, markup)
    }

    private fun handleStatusMenu(callback: org.telegram.telegrambots.meta.api.objects.CallbackQuery) {
        val (reply, markup) = statusReply(callback.from.id)
        editCallbackMessage(callback, reply, markup)
    }

    private fun handleLogsMenu(callback: org.telegram.telegrambots.meta.api.objects.CallbackQuery) {
        val reply = logsReply(callback.from.id, "/logs")
        editCallbackMessage(callback, reply, mainMenuMarkup())
    }

    private fun handleLinkMenu(callback: org.telegram.telegrambots.meta.api.objects.CallbackQuery) {
        val (reply, markup) = linkText(callback.from.id)
        editCallbackMessage(callback, reply, markup)
    }

    private fun handleActionCreate(callback: org.telegram.telegrambots.meta.api.objects.CallbackQuery) {
        val rows = listOf(
            InlineKeyboardRow(listOf(
                InlineKeyboardButton.builder().text("From template").callbackData("action_template_list").build(),
                InlineKeyboardButton.builder().text("From scratch").callbackData("action_create_scratch").build()
            )),
            InlineKeyboardRow(listOf(
                InlineKeyboardButton.builder().text("Back").callbackData("back_actions").build()
            ))
        )
        val markup = InlineKeyboardMarkup.builder().keyboard(rows).build()
        editCallbackMessage(callback, "Create a new action:", markup)
    }

    private fun handleActionCreateScratch(callback: org.telegram.telegrambots.meta.api.objects.CallbackQuery) {
        botActions.beginActionCreate(callback.from.id)
        editCallbackMessage(callback, "Send the new action name.")
    }

    private fun handleActionCreateDefault(callback: org.telegram.telegrambots.meta.api.objects.CallbackQuery) {
        val user = dataStore.getUser(callback.from.id)
        val name = user?.pendingActionCreate?.name ?: "Action"
        val action = botActions.createAction(callback.from.id, name)
        dataStore.upsertUser(callback.from.id) { current ->
            current.copy(pendingActionCreate = null)
        }
        val picker = buildActivityPickerForAction(callback.from.id, action.id)
        editCallbackMessage(callback, picker.first, picker.second)
    }

    private fun handleActionTemplateList(callback: org.telegram.telegrambots.meta.api.objects.CallbackQuery) {
        val templateRows = stravahooks.actions.ActionTemplates.ALL.map { template ->
            InlineKeyboardRow(listOf(
                InlineKeyboardButton.builder()
                    .text("${template.name}: ${template.description}")
                    .callbackData("action_from_template:${template.id}")
                    .build()
            ))
        }.toMutableList()
        templateRows.add(InlineKeyboardRow(listOf(
            InlineKeyboardButton.builder().text("Back").callbackData("action_create").build()
        )))
        val markup = InlineKeyboardMarkup.builder().keyboard(templateRows).build()
        editCallbackMessage(callback, "Pick a template:", markup)
    }

    private fun handleActionFromTemplate(callback: org.telegram.telegrambots.meta.api.objects.CallbackQuery, templateId: String) {
        val template = stravahooks.actions.ActionTemplates.findById(templateId)
        if (template == null) {
            editCallbackMessage(callback, "Template not found.")
        } else {
            val action = botActions.createAction(callback.from.id, template.name)
            botActions.updateActionCode(callback.from.id, action.id, template.code)
            val picker = buildActivityPickerForAction(callback.from.id, action.id)
            editCallbackMessage(callback, "Created action \"${template.name}\" from template.\n\n${picker.first}", picker.second)
        }
    }

    private fun handleActionShow(callback: org.telegram.telegrambots.meta.api.objects.CallbackQuery, id: String) {
        val action = botActions.getAction(callback.from.id, id)
        val text = if (action == null) {
            "Action not found."
        } else {
            val status = if (action.enabled) "enabled" else "disabled"
            val name = htmlEscape(action.name)
            val code = htmlEscape(action.code)
            val desc = action.description?.let { "\n${htmlEscape(it)}" } ?: ""
            "Action \"${name}\" ($status)$desc\n<pre><code>$code</code></pre>"
        }
        editCallbackMessage(callback, text, actionDetailMarkup(action), ParseMode.HTML)
    }

    private fun handleActionEdit(callback: org.telegram.telegrambots.meta.api.objects.CallbackQuery, chatId: String, id: String) {
        val action = botActions.getAction(callback.from.id, id)
        val text = if (action == null) {
            "Action not found."
        } else {
            botActions.beginActionEdit(callback.from.id, action.id)
            "Send the new code for action \"${action.name}\"."
        }
        val message = SendMessage.builder()
            .chatId(chatId)
            .text(text)
            .build()
        try {
            client.execute(message)
        } catch (e: TelegramApiException) {
            logger.warn("Failed to send message", e)
        }
    }

    private fun handleActionEditDesc(callback: org.telegram.telegrambots.meta.api.objects.CallbackQuery, chatId: String, id: String) {
        val action = botActions.getAction(callback.from.id, id)
        val text = if (action == null) {
            "Action not found."
        } else {
            botActions.beginActionEdit(callback.from.id, action.id, "description")
            "Send the new description for action \"${action.name}\"."
        }
        val message = SendMessage.builder()
            .chatId(chatId)
            .text(text)
            .build()
        try {
            client.execute(message)
        } catch (e: TelegramApiException) {
            logger.warn("Failed to send message", e)
        }
    }

    private fun handleActionCheck(callback: org.telegram.telegrambots.meta.api.objects.CallbackQuery, id: String) {
        val picker = buildActivityPickerForAction(callback.from.id, id)
        editCallbackMessage(callback, picker.first, picker.second)
    }

    private fun handleActionToggle(callback: org.telegram.telegrambots.meta.api.objects.CallbackQuery, id: String) {
        val toggled = botActions.toggleAction(callback.from.id, id)
        val action = botActions.getAction(callback.from.id, id)
        val text = if (!toggled || action == null) {
            "Action not found."
        } else {
            val status = if (action.enabled) "enabled" else "disabled"
            "Action ${action.name} is now $status."
        }
        editCallbackMessage(callback, text, actionDetailMarkup(action))
    }

    private fun handleActionDelete(callback: org.telegram.telegrambots.meta.api.objects.CallbackQuery, id: String) {
        val action = botActions.getAction(callback.from.id, id)
        if (action == null) {
            editCallbackMessage(callback, "Action not found.", actionDetailMarkup(null))
        } else {
            botActions.beginActionDelete(callback.from.id, id)
            val name = htmlEscape(action.name)
            val code = htmlEscape(action.code)
            val text = "Delete action \"${name}\"?\n<pre><code>$code</code></pre>"
            editCallbackMessage(callback, text, actionDeleteMarkup(action.id), ParseMode.HTML)
        }
    }

    private fun handleActionDeleteConfirm(callback: org.telegram.telegrambots.meta.api.objects.CallbackQuery, id: String) {
        val deletedName = botActions.deleteAction(callback.from.id, id)
        if (deletedName == null) {
            editCallbackMessage(callback, "Action not found.", actionDetailMarkup(null))
        } else {
            val (reply, markup) = actionsReply(callback.from.id, "/actions")
            editCallbackMessage(callback, "Deleted action \"$deletedName\".\n\n$reply", markup)
        }
    }

    private fun handleActionDeleteCancel(callback: org.telegram.telegrambots.meta.api.objects.CallbackQuery, id: String) {
        botActions.clearPendingActionDelete(callback.from.id, id)
        val action = botActions.getAction(callback.from.id, id)
        val text = if (action == null) {
            "Action not found."
        } else {
            val status = if (action.enabled) "enabled" else "disabled"
            val name = htmlEscape(action.name)
            val code = htmlEscape(action.code)
            "Action \"${name}\" ($status)\n<pre><code>$code</code></pre>"
        }
        editCallbackMessage(callback, text, actionDetailMarkup(action), ParseMode.HTML)
    }

    private fun handleApplyConfirm(callback: org.telegram.telegrambots.meta.api.objects.CallbackQuery, id: String) {
        val result = botActions.applyPending(callback.from.id, id)
        val text = when (result) {
            is BotActions.ApplyResult.Success -> "Applied changes to activity ${result.activityId}."
            is BotActions.ApplyResult.Failed -> "Failed to apply changes to activity ${result.activityId}."
            is BotActions.ApplyResult.Error -> result.message
        }
        editCallbackMessage(callback, text, mainMenuMarkup())
    }

    private fun handleApplyCancel(callback: org.telegram.telegrambots.meta.api.objects.CallbackQuery) {
        botActions.clearPendingApply(callback.from.id)
        editCallbackMessage(callback, "Apply canceled.", mainMenuMarkup())
    }

    private fun handleApplyPreview(callback: org.telegram.telegrambots.meta.api.objects.CallbackQuery, id: String) {
        val result = botActions.previewApply(callback.from.id, id)
        val (text, markup) = formatPreviewResult(result)
        editCallbackMessage(callback, text, markup)
    }

    private fun handleApplyPick(callback: org.telegram.telegrambots.meta.api.objects.CallbackQuery, id: String) {
        val picker = buildActionPickerSingle(callback.from.id, id)
        editCallbackMessage(callback, picker.first, picker.second)
    }

    private fun handleApplyPreviewAction(callback: org.telegram.telegrambots.meta.api.objects.CallbackQuery, suffix: String) {
        val parts = suffix.split(":")
        if (parts.size == 2) {
            val activityId = parts[0]
            val actionId = parts[1]
            val result = botActions.previewApplySingle(callback.from.id, activityId, actionId)
            val (text, markup) = formatPreviewResult(result)
            editCallbackMessage(callback, text, markup)
        }
    }

    private fun startReply(telegramUserId: Long): Pair<String, InlineKeyboardMarkup?> {
        val user = dataStore.getUser(telegramUserId)
        if (user?.strava != null) {
            return mainMenuReply(telegramUserId)
        }
        val (_, markup) = linkText(telegramUserId)
        val text = """
            Welcome to StravaHooks!

            This bot lets you create JavaScript actions that automatically edit your Strava activities (rename, mute, set commute, etc).

            To get started, link your Strava account using the button below.
            After linking, use /actions to create and manage actions, and /apply to preview them on recent activities.

            Use /help to see all available commands.
        """.trimIndent()
        return text to markup
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
                logger.warn("Failed to send message", e)
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
            logger.warn("Failed to edit message", e)
        }
    }

    private fun statusReply(telegramUserId: Long): Pair<String, InlineKeyboardMarkup?> {
        val user = dataStore.getUser(telegramUserId)
        val account = user?.strava ?: return "Account: not linked." to null
        val label = account.athleteName
            ?: account.athleteId?.let { "athlete $it" }
            ?: "unknown athlete"
        val expiresAt = Instant.ofEpochSecond(account.expiresAt)
        val expiresText = DateTimeFormatter.ISO_INSTANT.format(expiresAt)
        val scopeSummary = ScopeChecker.scopeSummary(account.scope)
        val scopeWarnings = ScopeChecker.scopeWarnings(account.scope)
        val text = """
            Account: linked as $label
            Scope: $scopeSummary
            Token expires: $expiresText
            $scopeWarnings
        """.trimIndent()
        val buttons = mutableListOf<InlineKeyboardButton>()
        if (!ScopeChecker.hasFullScope(account.scope)) {
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

    private fun buildUpgradeButton(telegramUserId: Long): InlineKeyboardButton? {
        val clientId = config.stravaClientId
        val baseUrl = config.baseUrl
        if (clientId.isNullOrBlank() || baseUrl.isNullOrBlank()) {
            return null
        }
        val state = oauthStateStore.issue(telegramUserId)
        val redirectUri = "$baseUrl/strava/oauth/callback"
        val scopes = REQUIRED_STRAVA_SCOPES.joinToString(",")
        val url = botActions.buildAuthorizeUrl(clientId, redirectUri, scopes, state)
        return InlineKeyboardButton.builder()
            .text("Upgrade scopes")
            .url(url)
            .build()
    }

    private fun actionsReply(telegramUserId: Long, text: String): Pair<String, InlineKeyboardMarkup?> {
        val parts = text.split(" ", limit = 4)
        if (parts.size >= 2 && (parts[1] == "new" || parts[1] == "create")) {
            val name = parts.getOrNull(2)?.trim().orEmpty()
            if (name.isBlank()) {
                return "Usage: /actions new <name>" to null
            }
            val action = botActions.createAction(telegramUserId, name)
            return "Created action ${action.name}. It starts disabled." to null
        }
        if (parts.size >= 4 && parts[1] == "code") {
            val id = parts[2].trim()
            val code = parts[3].trim()
            if (id.isBlank() || code.isBlank()) {
                return "Usage: /actions code <id> <javascript>" to null
            }
            val syntaxError = actionEngine.validateSyntax(code)
            if (syntaxError != null) {
                return "Syntax error:\n$syntaxError" to null
            }
            val updated = botActions.updateActionCode(telegramUserId, id, code)
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
            val action = botActions.getAction(telegramUserId, id)
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
        return "Actions:\n$list" to actionsKeyboard(actions)
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
            [${entry.mode.name.lowercase()}] activity ${activityUrl(entry.activityId)} • ${entry.result.name.lowercase()}
            actions: $actions
            changes: ${changes.ifBlank { "none" }}
            logs: $logsText
            """.trimIndent()
        }
    }

    private fun applyReply(telegramUserId: Long, text: String): Pair<String, InlineKeyboardMarkup?> {
        val user = dataStore.getUser(telegramUserId)
            ?: return "Account not linked yet." to null
        val account = botActions.refreshAccountIfNeeded(user)
            ?: return "Account not linked yet." to null

        val actions = user.actions.sortedBy { it.order }
        if (actions.isEmpty()) {
            return "No actions yet. Create one first." to null
        }

        val recent = runBlocking { stravaApi.fetchRecentActivities(account.accessToken, 5) }
        if (recent.isEmpty()) {
            return "No recent activities found." to null
        }
        val rows = recent.filter { it.id != null }.map { activity ->
            val distanceText = botActions.formatDistanceKm(activity.distance)
            val timeText = botActions.formatDuration(activity.movingTime)
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
        val account = botActions.refreshAccountIfNeeded(user)
            ?: return "Account not linked yet." to null
        val recent = runBlocking { stravaApi.fetchRecentActivities(account.accessToken, 5) }
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

    private fun formatPreviewResult(
        result: BotActions.PreviewResult
    ): Pair<String, InlineKeyboardMarkup?> {
        return when (result) {
            is BotActions.PreviewResult.Changes -> {
                val activityId = result.activityId
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
                val header = activityHeader(result.activity)
                val logText = if (result.logs.isEmpty()) "" else "\nLogs:\n" + result.logs.joinToString("\n")
                val actionLabel = if (result.actionNames.size == 1) {
                    "\nAction: ${result.actionNames.first()}"
                } else {
                    ""
                }
                val text = """
                    $header$actionLabel
                    Changes:
                    ${result.summary}$logText
                """.trimIndent()
                text to markup
            }
            is BotActions.PreviewResult.NoChanges ->
                "No changes proposed for activity ${result.activityId}." to null
            is BotActions.PreviewResult.ActionError ->
                "Action error:\n${result.message}" to null
            is BotActions.PreviewResult.ValidationError ->
                "Validation error:\n${result.message}" to null
            is BotActions.PreviewResult.NotLinked ->
                result.message to null
            is BotActions.PreviewResult.NotFound ->
                result.message to null
        }
    }

    private fun activityHeader(activity: StravaActivity): String {
        val name = activity.name ?: "Untitled"
        val dateText = activity.startDate?.take(10) ?: "unknown date"
        val distance = botActions.formatDistanceKm(activity.distance)
        val time = botActions.formatDuration(activity.movingTime)
        val url = activity.id?.let { activityUrl(it) } ?: "unknown"
        return "Activity: $name • $distance • $time • $dateText • $url"
    }

    private fun htmlEscape(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }


    private fun formatPendingEditResult(
        result: BotActions.PendingEditResult,
        telegramUserId: Long
    ): Triple<String, InlineKeyboardMarkup?, String?> {
        return when (result) {
            is BotActions.PendingEditResult.CodeUpdated ->
                Triple("Updated code for ${result.actionName}.", mainMenuMarkup(), null)
            is BotActions.PendingEditResult.DescriptionUpdated ->
                Triple("Updated description for ${result.actionName}.", mainMenuMarkup(), null)
            is BotActions.PendingEditResult.ActionNotFound ->
                Triple(result.message, mainMenuMarkup(), null)
            is BotActions.PendingEditResult.SyntaxError ->
                Triple("Syntax error:\n${result.error}\n\nPlease send corrected code.", null, null)
            is BotActions.PendingEditResult.NameReceived ->
                Triple(
                    "Send the action code now (or keep default). Example:\n\n${BotActions.DEFAULT_ACTION_CODE}",
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
            is BotActions.PendingEditResult.ActionCreated -> {
                val picker = buildActivityPickerForAction(telegramUserId, result.actionId)
                Triple(picker.first, picker.second, "HTML")
            }
            is BotActions.PendingEditResult.CreationSyntaxError ->
                Triple("Syntax error:\n${result.error}\n\nPlease send corrected code.", null, null)
        }
    }

    private fun linkText(telegramUserId: Long?): Pair<String, InlineKeyboardMarkup?> =
        botActions.linkText(telegramUserId)

    private fun accountStatus(telegramUserId: Long?): Pair<Boolean, String> =
        botActions.accountStatus(telegramUserId)

}

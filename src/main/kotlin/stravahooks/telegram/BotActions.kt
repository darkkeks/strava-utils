package stravahooks.telegram

import stravahooks.actions.ActionEngine
import stravahooks.actions.activityUrl
import stravahooks.actions.buildChangeSummary
import stravahooks.config.StravaHooksConfig
import stravahooks.storage.ActionCreateStage
import stravahooks.storage.ActionDefinition
import stravahooks.storage.ActivityUpdate
import stravahooks.storage.ApplyLogEntry
import stravahooks.storage.ApplyMode
import stravahooks.storage.ApplyResult as ApplyResultEnum
import stravahooks.storage.ChangePair
import stravahooks.storage.DataStore
import stravahooks.storage.PendingActionCreate
import stravahooks.storage.PendingActionDelete
import stravahooks.storage.PendingActionEdit
import stravahooks.storage.PendingApply
import stravahooks.storage.StoredUser
import stravahooks.storage.StravaAccount
import stravahooks.strava.REQUIRED_STRAVA_SCOPES
import stravahooks.strava.StravaApi
import stravahooks.strava.StravaActivity
import stravahooks.strava.TokenRefresher
import stravahooks.oauth.OAuthStateStore
import kotlinx.coroutines.runBlocking
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow

class BotActions(
    private val config: StravaHooksConfig,
    private val dataStore: DataStore,
    private val actionEngine: ActionEngine,
    private val stravaApi: StravaApi,
    private val oauthStateStore: OAuthStateStore? = null,
    private val tokenRefresher: TokenRefresher = TokenRefresher(config, dataStore, stravaApi)
) {
    // --- Result types ---

    sealed class PreviewResult {
        data class Changes(
            val activityId: Long,
            val activity: StravaActivity,
            val summary: String,
            val logs: List<String>,
            val actionNames: List<String>,
            val actionIds: List<String>
        ) : PreviewResult()
        data class NoChanges(val activityId: Long, val logs: List<String>) : PreviewResult()
        data class ActionError(val message: String) : PreviewResult()
        data class ValidationError(val message: String) : PreviewResult()
        data class NotLinked(val message: String = "Account not linked yet.") : PreviewResult()
        data class NotFound(val message: String) : PreviewResult()
    }

    sealed class ApplyResult {
        data class Success(val activityId: Long) : ApplyResult()
        data class Failed(val activityId: Long, val error: String?) : ApplyResult()
        data class Error(val message: String) : ApplyResult()
    }

    sealed class PendingEditResult {
        data class CodeUpdated(val actionName: String) : PendingEditResult()
        data class DescriptionUpdated(val actionName: String) : PendingEditResult()
        data class ActionNotFound(val message: String = "Action not found.") : PendingEditResult()
        data class SyntaxError(val error: String) : PendingEditResult()
        data class NameReceived(val name: String) : PendingEditResult()
        data class ActionCreated(val actionId: String, val telegramUserId: Long) : PendingEditResult()
        data class CreationSyntaxError(val error: String) : PendingEditResult()
    }

    // --- Action CRUD ---

    fun createAction(telegramUserId: Long, name: String): ActionDefinition {
        val now = Instant.now().epochSecond
        val id = UUID.randomUUID().toString().substring(0, ACTION_ID_LENGTH)
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

    fun updateActionCode(telegramUserId: Long, id: String, code: String): Boolean {
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

    fun updateActionDescription(telegramUserId: Long, id: String, description: String?): Boolean {
        val now = Instant.now().epochSecond
        var found = false
        dataStore.upsertUser(telegramUserId) { user ->
            val updated = user.actions.map { action ->
                if (action.id == id) {
                    found = true
                    action.copy(description = description, updatedAt = now)
                } else {
                    action
                }
            }
            user.copy(actions = updated)
        }
        return found
    }

    fun getAction(telegramUserId: Long, id: String): ActionDefinition? {
        val user = dataStore.getUser(telegramUserId) ?: return null
        return user.actions.firstOrNull { it.id == id }
    }

    fun toggleAction(telegramUserId: Long, id: String): Boolean {
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

    fun deleteAction(telegramUserId: Long, id: String): String? {
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

    // --- State management ---

    fun beginActionEdit(telegramUserId: Long, actionId: String, field: String = "code") {
        dataStore.upsertUser(telegramUserId) { user ->
            user.copy(
                pendingActionEdit = PendingActionEdit(actionId, Instant.now().epochSecond, field),
                pendingActionDelete = null,
                pendingActionCreate = null
            )
        }
    }

    fun beginActionDelete(telegramUserId: Long, actionId: String) {
        dataStore.upsertUser(telegramUserId) { user ->
            user.copy(
                pendingActionDelete = PendingActionDelete(actionId, Instant.now().epochSecond),
                pendingActionEdit = null,
                pendingActionCreate = null
            )
        }
    }

    fun clearPendingActionDelete(telegramUserId: Long, actionId: String) {
        dataStore.upsertUser(telegramUserId) { user ->
            if (user.pendingActionDelete?.actionId != actionId) {
                user
            } else {
                user.copy(pendingActionDelete = null)
            }
        }
    }

    fun beginActionCreate(telegramUserId: Long) {
        dataStore.upsertUser(telegramUserId) { user ->
            user.copy(
                pendingActionCreate = PendingActionCreate(Instant.now().epochSecond, ActionCreateStage.NAME),
                pendingActionDelete = null,
                pendingActionEdit = null
            )
        }
    }

    fun clearPendingApply(telegramUserId: Long) {
        dataStore.upsertUser(telegramUserId) { current ->
            current.copy(pendingApply = null)
        }
    }

    // --- Preview / Apply ---

    fun previewApplySingle(
        telegramUserId: Long,
        activityIdText: String,
        actionId: String
    ): PreviewResult {
        val activityId = activityIdText.toLongOrNull()
            ?: return PreviewResult.NotFound("Invalid activity id: $activityIdText")
        val user = dataStore.getUser(telegramUserId) ?: return PreviewResult.NotLinked()
        val account = refreshAccountIfNeeded(user) ?: return PreviewResult.NotLinked()
        val action = user.actions.firstOrNull { it.id == actionId }
            ?: return PreviewResult.NotFound("Action not found.")

        val activity = runBlocking { stravaApi.fetchActivity(account.accessToken, activityId) }
            ?: return PreviewResult.NotFound("Failed to fetch activity $activityId.")

        val normalized = actionEngine.normalizeActivity(activity)
        val mutableNormalized = normalized.toMutableMap()
        val before = actionEngine.snapshotWritable(mutableNormalized)
        val run = actionEngine.runActions(listOf(action), mutableNormalized)
        if (run.errors.isNotEmpty()) {
            writeApplyLog(
                telegramUserId, activityId, listOf(action),
                ApplyMode.PREVIEW, "Action error", emptyMap(), run.logs,
                ApplyResultEnum.ERROR, run.errors.joinToString("; ")
            )
            return PreviewResult.ActionError(run.errors.joinToString("\n"))
        }
        val validation = actionEngine.validateChanges(normalized, mutableNormalized)
        if (!validation.isValid) {
            writeApplyLog(
                telegramUserId, activityId, listOf(action),
                ApplyMode.PREVIEW, "Validation error", emptyMap(), run.logs,
                ApplyResultEnum.ERROR, validation.errorMessage()
            )
            return PreviewResult.ValidationError(validation.errorMessage())
        }
        val changes = actionEngine.diffWritable(before, mutableNormalized)
        if (changes.isEmpty()) {
            writeApplyLog(
                telegramUserId, activityId, listOf(action),
                ApplyMode.PREVIEW, "No changes", emptyMap(), run.logs,
                ApplyResultEnum.NO_CHANGES, null
            )
            return PreviewResult.NoChanges(activityId, run.logs)
        }

        val summary = buildChangeSummary(changes)
        val update = actionEngine.buildUpdate(changes)
        val changePairs = actionEngine.toChangePairs(changes)
        val pending = PendingApply(
            activityId = activityId,
            actionIds = listOf(action.id),
            update = update,
            summary = summary,
            changes = changePairs,
            createdAt = Instant.now().epochSecond
        )
        dataStore.upsertUser(telegramUserId) { current ->
            current.copy(pendingApply = pending)
        }
        writeApplyLog(
            telegramUserId, activityId, listOf(action),
            ApplyMode.PREVIEW, summary, changePairs, run.logs,
            ApplyResultEnum.PREVIEW_READY, null
        )
        return PreviewResult.Changes(activityId, activity, summary, run.logs, listOf(action.name), listOf(action.id))
    }

    fun previewApply(telegramUserId: Long, activityIdText: String): PreviewResult {
        val activityId = activityIdText.toLongOrNull()
            ?: return PreviewResult.NotFound("Invalid activity id: $activityIdText")
        val user = dataStore.getUser(telegramUserId) ?: return PreviewResult.NotLinked()
        val account = refreshAccountIfNeeded(user) ?: return PreviewResult.NotLinked()

        val actions = user.actions.filter { it.enabled }.sortedBy { it.order }
        if (actions.isEmpty()) {
            return PreviewResult.NotFound("No enabled actions. Enable an action first.")
        }

        val activity = runBlocking { stravaApi.fetchActivity(account.accessToken, activityId) }
            ?: return PreviewResult.NotFound("Failed to fetch activity $activityId.")

        val normalized = actionEngine.normalizeActivity(activity)
        val mutableNormalized = normalized.toMutableMap()
        val before = actionEngine.snapshotWritable(mutableNormalized)
        val run = actionEngine.runActions(actions, mutableNormalized)
        if (run.errors.isNotEmpty()) {
            writeApplyLog(
                telegramUserId, activityId, actions,
                ApplyMode.PREVIEW, "Action error", emptyMap(), run.logs,
                ApplyResultEnum.ERROR, run.errors.joinToString("; ")
            )
            return PreviewResult.ActionError(run.errors.joinToString("\n"))
        }
        val validation = actionEngine.validateChanges(normalized, mutableNormalized)
        if (!validation.isValid) {
            writeApplyLog(
                telegramUserId, activityId, actions,
                ApplyMode.PREVIEW, "Validation error", emptyMap(), run.logs,
                ApplyResultEnum.ERROR, validation.errorMessage()
            )
            return PreviewResult.ValidationError(validation.errorMessage())
        }
        val changes = actionEngine.diffWritable(before, mutableNormalized)
        if (changes.isEmpty()) {
            writeApplyLog(
                telegramUserId, activityId, actions,
                ApplyMode.PREVIEW, "No changes", emptyMap(), run.logs,
                ApplyResultEnum.NO_CHANGES, null
            )
            return PreviewResult.NoChanges(activityId, run.logs)
        }

        val summary = buildChangeSummary(changes)
        val update = actionEngine.buildUpdate(changes)
        val changePairs = actionEngine.toChangePairs(changes)
        val pending = PendingApply(
            activityId = activityId,
            actionIds = actions.map { it.id },
            update = update,
            summary = summary,
            changes = changePairs,
            createdAt = Instant.now().epochSecond
        )
        dataStore.upsertUser(telegramUserId) { current ->
            current.copy(pendingApply = pending)
        }
        writeApplyLog(
            telegramUserId, activityId, actions,
            ApplyMode.PREVIEW, summary, changePairs, run.logs,
            ApplyResultEnum.PREVIEW_READY, null
        )
        return PreviewResult.Changes(
            activityId, activity, summary, run.logs, actions.map { it.name }, actions.map { it.id }
        )
    }

    fun applyPending(telegramUserId: Long, activityIdText: String): ApplyResult {
        val user = dataStore.getUser(telegramUserId) ?: return ApplyResult.Error("Account not linked yet.")
        val account = refreshAccountIfNeeded(user) ?: return ApplyResult.Error("Account not linked yet.")
        val scopeSet = account.scope.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (!scopeSet.contains("activity:write")) {
            return ApplyResult.Error("Missing activity:write scope. Use /status to upgrade.")
        }
        val pending = user.pendingApply ?: return ApplyResult.Error("No pending apply.")
        if (pending.activityId.toString() != activityIdText) {
            return ApplyResult.Error("Pending activity does not match.")
        }
        val body = actionEngine.buildUpdateBody(pending.update)
        val result = runBlocking { stravaApi.updateActivity(account.accessToken, pending.activityId, body) }
        clearPendingApply(telegramUserId)
        val actions = user.actions.filter { pending.actionIds.contains(it.id) }.sortedBy { it.order }
        writeApplyLog(
            telegramUserId = telegramUserId,
            activityId = pending.activityId,
            actions = actions,
            mode = ApplyMode.APPLY,
            summary = pending.summary,
            changes = pending.changes,
            logs = emptyList(),
            result = if (result.success) ApplyResultEnum.APPLIED else ApplyResultEnum.FAILED,
            error = result.error ?: result.body
        )
        return if (result.success) {
            ApplyResult.Success(pending.activityId)
        } else {
            ApplyResult.Failed(pending.activityId, result.error ?: result.body)
        }
    }

    // --- Pending edit handling ---

    fun tryHandlePendingEdit(telegramUserId: Long, messageText: String): PendingEditResult? {
        val user = dataStore.getUser(telegramUserId) ?: return null
        val code = messageText.trim()
        if (code.isBlank() || code.startsWith("/")) {
            return null
        }
        val pendingEdit = user.pendingActionEdit
        if (pendingEdit != null) {
            if (pendingEdit.field == "description") {
                val updated = updateActionDescription(telegramUserId, pendingEdit.actionId, code)
                val name = getAction(telegramUserId, pendingEdit.actionId)?.name ?: "action"
                dataStore.upsertUser(telegramUserId) { current ->
                    current.copy(pendingActionEdit = null)
                }
                return if (updated) {
                    PendingEditResult.DescriptionUpdated(name)
                } else {
                    PendingEditResult.ActionNotFound()
                }
            }
            val syntaxError = actionEngine.validateSyntax(code)
            if (syntaxError != null) {
                return PendingEditResult.SyntaxError(syntaxError)
            }
            val updated = updateActionCode(telegramUserId, pendingEdit.actionId, code)
            val name = getAction(telegramUserId, pendingEdit.actionId)?.name ?: "action"
            dataStore.upsertUser(telegramUserId) { current ->
                current.copy(pendingActionEdit = null)
            }
            return if (updated) {
                PendingEditResult.CodeUpdated(name)
            } else {
                PendingEditResult.ActionNotFound()
            }
        }
        val pendingCreate = user.pendingActionCreate
        if (pendingCreate != null) {
            if (pendingCreate.stage == ActionCreateStage.NAME) {
                dataStore.upsertUser(telegramUserId) { current ->
                    current.copy(pendingActionCreate = PendingActionCreate(Instant.now().epochSecond, ActionCreateStage.CODE, code))
                }
                return PendingEditResult.NameReceived(code)
            }
            if (pendingCreate.stage == ActionCreateStage.CODE) {
                val syntaxError = actionEngine.validateSyntax(code)
                if (syntaxError != null) {
                    return PendingEditResult.CreationSyntaxError(syntaxError)
                }
                val action = createAction(telegramUserId, pendingCreate.name ?: "Action")
                updateActionCode(telegramUserId, action.id, code)
                dataStore.upsertUser(telegramUserId) { current ->
                    current.copy(pendingActionCreate = null)
                }
                return PendingEditResult.ActionCreated(action.id, telegramUserId)
            }
        }
        return null
    }

    // --- Helpers ---

    fun refreshAccountIfNeeded(user: StoredUser): StravaAccount? {
        return tokenRefresher.refreshAccountIfNeeded(user)
    }

    fun writeApplyLog(
        telegramUserId: Long,
        activityId: Long,
        actions: List<ActionDefinition>,
        mode: ApplyMode,
        summary: String,
        changes: Map<String, ChangePair>,
        logs: List<String>,
        result: ApplyResultEnum,
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

    fun formatDistanceKm(distanceMeters: Double?): String {
        if (distanceMeters == null) {
            return "?"
        }
        val km = distanceMeters / 1000.0
        return String.format("%.1f km", km)
    }

    fun formatDuration(seconds: Int?): String {
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

    fun accountStatus(telegramUserId: Long?): Pair<Boolean, String> {
        if (telegramUserId == null) {
            return false to "Account: unknown."
        }
        val user = dataStore.getUser(telegramUserId)
        val account = user?.strava
        if (account == null) {
            return false to "Account: not linked."
        }
        val label = account.athleteName
            ?: account.athleteId?.let { "athlete $it" }
            ?: "unknown athlete"
        return true to "Account: linked as $label."
    }

    fun linkText(telegramUserId: Long?): Pair<String, InlineKeyboardMarkup?> {
        val clientId = config.stravaClientId
        val baseUrl = config.baseUrl
        if (clientId.isNullOrBlank() || baseUrl.isNullOrBlank()) {
            return "Linking is not configured yet. Ask the admin to set strava_client_id and base_url." to null
        }
        if (telegramUserId == null) {
            return "Unable to determine your Telegram user ID." to null
        }
        val store = oauthStateStore
            ?: return "OAuth state store not available." to null
        val state = store.issue(telegramUserId)
        val redirectUri = "$baseUrl/strava/oauth/callback"
        val scopes = REQUIRED_STRAVA_SCOPES.joinToString(",")
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

    fun buildAuthorizeUrl(
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

    companion object {
        private const val ACTION_ID_LENGTH = 8
        const val DEFAULT_ACTION_CODE = """
function action(activity) {
  // TODO: edit activity fields here
}
"""
    }
}

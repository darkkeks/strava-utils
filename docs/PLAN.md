# Polished MVP Implementation Plan

## Context

DESIGN.md describes a Strava automation bot with ~20 feature areas. The core loop works (OAuth, action CRUD, Rhino JS execution, preview/apply, polling), but several robustness features, validations, and UX improvements are missing. This plan covers everything needed for a polished MVP, ordered by dependency and value. We maximize automated testing by extracting interfaces for external dependencies and testing pure logic directly.

## Principles

- **Test pure logic directly** — ActionEngine, ActionRunner, DataStore, templates are trivially testable
- **Extract interfaces for I/O boundaries** — `StravaApi` interface for `StravaClient`, `NotificationSender` for Telegram messages from poller
- **No mocking library** — use hand-written fakes (`FakeStravaApi`, `FakeNotificationSender`) instead of MockK
- **Extract BotActions** — move bot business logic (action CRUD, preview, apply) into a testable class; the thin Telegram dispatch layer stays in `StravaHooksBot`

---

## Step 0: Baseline Test Coverage

Bring existing pure-logic classes under test before changing them.

### 0a. ActionEngine tests
**New file**: `src/test/kotlin/stravahooks/actions/ActionEngineTest.kt`

Tests:
- `normalizeActivity` — correct keys, unit conversions (`averageSpeed * 3.6`), null handling
- `snapshotWritable` — returns exactly the 6 writable fields
- `diffWritable` — empty when equal, only changed fields, null↔value transitions
- `buildUpdate` — maps diff pairs to `ActivityUpdate`
- `buildUpdateBody` — only includes fields in `update.fields`
- `toChangePairs` / `toChangePairsFromSummary` round-trip

### 0b. DataStore tests
**New file**: `src/test/kotlin/stravahooks/storage/DataStoreTest.kt`

Uses `Files.createTempDirectory` (same pattern as existing `ConfigLoaderTest`).

Tests:
- `load` returns empty state for nonexistent file
- `save` + `load` round-trip
- `upsertUser` creates and updates users
- `putOAuthState` + `consumeOAuthState` — valid nonce, expired nonce, double-consume
- `appendApplyLog` + `getApplyLogs` — ordering, limit, MAX_LOG_ENTRIES cap

### 0c. ActionRunner edge cases
**Extend**: `src/test/kotlin/stravahooks/actions/ActionRunnerTest.kt`

Tests:
- Syntax error returns `ActionRunResult(error = ...)`
- Runtime exception returns error
- `console.log` output captured correctly
- Setting field to JS `null`/`undefined` → Kotlin `null`

**Auto-tested**: All. **Manual**: None.

---

## Step 1: Syntax Validation

Validate JS before saving action code. Prevents broken scripts from being stored.

### Changes
**`ActionRunner.kt`** — add `validateSyntax(code: String): String?`:
- Calls `prepareSource(code)`, then `context.compileString(...)` in a try/catch
- Returns `null` on success, error message on failure

**`ActionEngine.kt`** — add `fun validateSyntax(code: String): String?` that delegates to `runner.validateSyntax(code)` (so the bot only talks to ActionEngine)

**`StravaHooksBot.kt`** — in `tryHandlePendingEdit`:
- Before saving code (both `pendingActionEdit` and `pendingActionCreate` "code" stage), call `actionEngine.validateSyntax(code)`
- If error: return the error message and keep pending state so user can retry

### Tests (in `ActionRunnerTest.kt`)
- Valid function → returns null
- Syntax error `if ( }` → returns error string
- Valid body-only code → returns null
- Body-only with syntax error → returns error string

**Auto-tested**: All validation logic. **Manual**: Send broken JS to bot, verify rejection + retry (~2 min).

---

## Step 2: Write-to-Readonly Detection + gear_id Validation

Detect when scripts modify non-writable fields or set invalid values.

### Changes
**`ActionEngine.kt`** — add:

```kotlin
data class ValidationResult(
    val readonlyWrites: List<String> = emptyList(),
    val invalidValues: List<String> = emptyList()
) {
    val isValid get() = readonlyWrites.isEmpty() && invalidValues.isEmpty()
    fun errorMessage(): String { ... }
}

fun validateChanges(before: Map<String, Any?>, after: Map<String, Any?>): ValidationResult
```

- Iterates all keys, finds changes to non-writable fields → `readonlyWrites`
- Checks `gear_id` is not empty string → `invalidValues`

**Callers** (bot preview + poller `pollOnce`):
- Snapshot full map before running actions, validate after
- On invalid: report error to user (bot) or log + skip (poller)

### Tests (in `ActionEngineTest.kt`)
- Only writable fields changed → valid
- `type` changed → readonlyWrites contains "type"
- `gear_id = ""` → invalidValues
- `gear_id = null` → valid (clearing gear)
- Multiple violations at once

**Integration test**: Run JS that writes `activity.type = "Walk"` through real ActionRunner + ActionEngine, verify validation catches it.

**Auto-tested**: All. **Manual**: Send a script writing `activity.distance_m = 0`, verify error (~2 min).

---

## Step 3: Safety Limits

Prevent runaway scripts and excessive edits.

### 3a. Script Instruction Limit

**`ActionRunner.kt`** — add a custom Rhino `ContextFactory`:
- Override `observeInstructionCount` to throw when exceeding limit (default 100,000)
- Override `makeContext` to set `instructionObserverThreshold = 10_000`
- Use this factory in `run()` and `validateSyntax()` instead of `ContextFactory.getGlobal()`

**`StravaHooksConfig.kt`** — add `val maxScriptInstructions: Int? = null` (null = use default 100k)

### 3b. Per-Poll Edit Cap

**`StravaHooksConfig.kt`** — add `val maxPollEditsPerCycle: Int? = null` (null = use default 10)

**`ActivityPoller.kt`** — track edit count in `pollOnce()`, break when cap reached

### 3c. Extract StravaApi Interface (enables testing for 3b and later steps)

**New file**: `src/main/kotlin/stravahooks/strava/StravaApi.kt` — interface with all public methods from `StravaClient`

**`StravaClient.kt`** — implements `StravaApi`

**`ActivityPoller.kt`** — constructor accepts `StravaApi` parameter (default `StravaClient()`)

**`StravaHooksBot.kt`** — constructor accepts `StravaApi` parameter (default `StravaClient()`)

**New test file**: `src/test/kotlin/stravahooks/strava/FakeStravaApi.kt` — in-memory implementation

### Tests
- `while(true){}` returns error about instruction limit, does not hang (`ActionRunnerTest`)
- Normal script succeeds under limit (`ActionRunnerTest`)

**New file**: `src/test/kotlin/stravahooks/polling/ActivityPollerTest.kt`

Uses `FakeStravaApi` + real `DataStore` on temp file:
- No users → does nothing
- User with no enabled actions → does nothing
- User + enabled action + new activity → action runs, update applied, log written
- 15 new activities + cap of 5 → only 5 updated
- Action error → logged, no update sent
- `lastPolledAt` updated after processing

**Auto-tested**: All. **Manual**: None.

---

## Step 4: Extract BotActions (Testable Business Logic)

Move bot business logic out of `StravaHooksBot.kt` (1355 lines) into a testable `BotActions` class. The bot becomes a thin Telegram I/O layer that delegates all decisions to `BotActions`.

### What gets extracted
**New file**: `src/main/kotlin/stravahooks/telegram/BotActions.kt`

Methods to extract from `StravaHooksBot` (these are currently private methods):
- Action CRUD: `createAction`, `updateActionCode`, `getAction`, `toggleAction`, `deleteAction` (lines 1004-1087)
- State management: `beginActionEdit`, `beginActionDelete`, `beginActionCreate`, `clearPendingActionDelete`, `clearPendingApply` (lines 1149-1186)
- Preview/apply: `previewApplySingle`, `previewApply`, `applyPending` (lines 727-643)
- Pending text dispatch: `tryHandlePendingEdit` (lines 1189-1248) — returns a result object instead of `Triple<String, Markup?, ParseMode?>`
- Token refresh: `refreshAccountIfNeeded` (lines 1311-1335)
- Logging: `writeApplyLog` (lines 946-972)
- Helpers: `buildChangeSummary`, `activityUrl`, `formatDistanceKm`, `formatDuration` (pure functions)

Constructor:
```kotlin
class BotActions(
    private val config: StravaHooksConfig,
    private val dataStore: DataStore,
    private val actionEngine: ActionEngine,
    private val stravaApi: StravaApi,
    private val oauthStateStore: OAuthStateStore
)
```

Return types become data objects instead of formatted strings:
```kotlin
sealed class PreviewResult {
    data class Changes(val activityId: Long, val summary: String, val logs: List<String>, val header: String, val actionNames: List<String>) : PreviewResult()
    data class NoChanges(val activityId: Long, val logs: List<String>) : PreviewResult()
    data class Error(val message: String) : PreviewResult()
}

sealed class ApplyResult {
    data class Success(val activityId: Long) : ApplyResult()
    data class Failed(val activityId: Long, val error: String?) : ApplyResult()
    data class Error(val message: String) : ApplyResult()
}
```

**`StravaHooksBot.kt`** becomes a thin dispatcher:
- Holds `BotActions` as a field (replaces internal `actionEngine` and `stravaClient`)
- `handleCallback` and `consume` call `BotActions` methods and format the results into Telegram messages
- All Telegram-specific code (inline keyboards, message building, `editCallbackMessage`) stays in the bot

### Tests
**New file**: `src/test/kotlin/stravahooks/telegram/BotActionsTest.kt`

Uses `FakeStravaApi` + real `DataStore` on temp file + real `ActionEngine`/`ActionRunner`:

1. `createAction` — new action in DataStore with correct defaults, `enabled=false`, order auto-assigned
2. `updateActionCode` with valid code — updates in DataStore, returns success
3. `updateActionCode` with syntax error — returns validation error (after Step 1 is in place)
4. `toggleAction` — flips enabled state
5. `deleteAction` — removes from DataStore, clears related pending state
6. `previewApplySingle` — runs action on FakeStravaApi activity, returns `Changes` with correct diff
7. `previewApplySingle` — with script writing readonly field, returns `Error` (after Step 2)
8. `previewApply` — runs all enabled actions in order, returns combined changes
9. `applyPending` — sends update through FakeStravaApi, writes apply log
10. `applyPending` — without `activity:write` scope, returns `Error`
11. `tryHandlePendingEdit` — routes pending code edit correctly
12. `tryHandlePendingEdit` — routes pending create name → code stages correctly

**Auto-tested**: All business logic. **Manual**: Smoke test bot in Telegram after refactor to verify messages still render correctly (~5 min).

---

## Step 5: Polling Notifications

Notify users via Telegram when polling applies changes or encounters errors.

### Changes
**New file**: `src/main/kotlin/stravahooks/telegram/NotificationSender.kt`

```kotlin
interface NotificationSender {
    fun sendNotification(telegramUserId: Long, text: String, parseMode: String? = null)
}
```

**New file**: `src/main/kotlin/stravahooks/telegram/TelegramNotificationSender.kt` — implements via `OkHttpTelegramClient`

**`ActivityPoller.kt`** — accepts `NotificationSender?` in constructor:
- On successful apply: notify with activity link + change summary
- On action error: notify with error details
- On no changes: no notification (too noisy)

**`Main.kt`** — wire `TelegramNotificationSender` into `ActivityPoller`

### Tests
**New file**: `src/test/kotlin/stravahooks/telegram/FakeNotificationSender.kt`

In `ActivityPollerTest.kt`:
- Successful apply → notification sent with activity URL + summary
- Action error → notification sent with error details
- No changes → no notification
- Null sender → no crash

**Auto-tested**: All notification dispatch logic. **Manual**: Trigger poll cycle, verify Telegram message (~3 min).

---

## Step 6: Onboarding and Help

Wire the existing `helpText()` to `/help`, improve `/start` for first-time users.

### Changes
**`StravaHooksBot.kt`**:
- Split `/start` and `/help` in the `when` block (line 76, currently both go to `mainMenuReply`)
- `/start` for unlinked users → intro message explaining the service + Link button
- `/start` for linked users → `mainMenuReply()` (existing behavior)
- `/help` → `helpText()` (already defined at line 269, just not wired)

### Tests
No new automated tests — this is pure UI text with a trivial `user?.strava == null` check.

**Manual**: Send `/start` as new user → see intro + link. Send `/help` → see command list (~2 min).

---

## Step 7: Action Templates

Offer pre-built templates when creating an action.

### Changes
**New file**: `src/main/kotlin/stravahooks/actions/ActionTemplates.kt`

```kotlin
data class ActionTemplate(val id: String, val name: String, val description: String, val code: String)

object ActionTemplates {
    val SHORT_RIDE_MUTE = ActionTemplate(...)
    val CONDITION_DESCRIPTION = ActionTemplate(...)
    val GEAR_SELECTION = ActionTemplate(...)
    val ALL = listOf(SHORT_RIDE_MUTE, CONDITION_DESCRIPTION, GEAR_SELECTION)
}
```

**`StravaHooksBot.kt`**:
- `action_create` callback → show menu: "From template" / "From scratch"
- New callbacks: `action_template_list` (shows 3 template buttons), `action_from_template:<templateId>` (creates action with template name + code, shows activity picker)

### Tests
**New file**: `src/test/kotlin/stravahooks/actions/ActionTemplatesTest.kt`

- All 3 templates pass syntax validation
- All 3 run without error on a sample activity
- SHORT_RIDE_MUTE: Ride with distance 5000m → mute=true, commute=true; Run → no changes
- CONDITION_DESCRIPTION: Run with 10km → appends to description
- GEAR_SELECTION: Ride → sets gear_id

**Auto-tested**: Template correctness. **Manual**: "Create action" → "From template" → pick one → verify (~3 min).

---

## Step 8: Action Description Editing

The `description` field exists in `ActionDefinition` but has no bot UI.

### Changes
**`DataStore.kt`** — extend `PendingActionEdit`:
```kotlin
data class PendingActionEdit(
    val actionId: String,
    val startedAt: Long,
    val field: String = "code"  // "code" or "description"
)
```

**`StravaHooksBot.kt`**:
- Add "Edit description" button to `actionDetailMarkup()` (line 1089)
- New callback `action_edit_desc:<id>` → sets pending edit with `field = "description"`, sends new message
- In `tryHandlePendingEdit` / `BotActions`, check `pendingEdit.field` to route to code update vs description update
- Show description in action detail view if present

**`BotActions.kt`** — add `updateActionDescription(telegramUserId, actionId, description)` method

### Tests
In `BotActionsTest.kt`:
- `updateActionDescription` updates description in DataStore
- `updateActionDescription` returns false for nonexistent action

**Auto-tested**: Description update logic. **Manual**: Tap "Edit description" → send text → verify in detail (~2 min).

---

## Step 9: Webhooks (Deferred to post-MVP)

Polling works and with notifications (Step 5) provides good UX. Webhooks add significant complexity for marginal MVP benefit. The `webhookVerifyToken` config field is already reserved.

If we decide to add it later:
- Add `GET /strava/webhook` (hub.challenge verification) and `POST /strava/webhook` (event delivery) in `StravaOAuthServer.kt` routing block
- Add `WebhookEventProcessor` to parse events and trigger action processing
- Test with Ktor's `testApplication`

---

## Execution Order and Dependencies

```
Step 0a (ActionEngine tests) ──┐
Step 0b (DataStore tests) ─────┤── can run in parallel
Step 0c (ActionRunner tests) ──┘
         │
Step 1 (Syntax validation) ──── depends on 0c
Step 2 (Readonly detection) ─── depends on 0a
Step 3 (Safety + StravaApi) ─── depends on 0a, 0b
         │
Step 4 (BotActions extraction) ─ depends on 3 (needs StravaApi interface)
         │
Step 5 (Poll notifications) ─── depends on 3
Step 6 (Onboarding) ─────────── independent (small)
Step 7 (Templates) ──────────── depends on 1 (needs validateSyntax for tests)
Step 8 (Description edit) ───── depends on 4 (adds to BotActions)
```

## Summary

| Step | Feature | New Auto Tests | Manual Test |
|------|---------|---------------|-------------|
| 0 | Baseline test coverage | ~22 | None |
| 1 | Syntax validation | 4 | ~2 min |
| 2 | Readonly + gear_id validation | 6 | ~2 min |
| 3 | Safety limits + StravaApi interface | ~8 + 6 poller tests | None |
| 4 | BotActions extraction | 12 | ~5 min |
| 5 | Polling notifications | 4 | ~3 min |
| 6 | Onboarding /start /help | 0 | ~2 min |
| 7 | Action templates | 6 | ~3 min |
| 8 | Description editing | 2 | ~2 min |
| **Total** | | **~70 tests** | **~19 min** |

## New Files

**Production**:
- `src/main/kotlin/stravahooks/strava/StravaApi.kt` — interface for StravaClient
- `src/main/kotlin/stravahooks/telegram/BotActions.kt` — extracted bot business logic
- `src/main/kotlin/stravahooks/actions/ActionTemplates.kt` — 3 template definitions
- `src/main/kotlin/stravahooks/telegram/NotificationSender.kt` — notification interface
- `src/main/kotlin/stravahooks/telegram/TelegramNotificationSender.kt` — Telegram implementation

**Test**:
- `src/test/kotlin/stravahooks/actions/ActionEngineTest.kt`
- `src/test/kotlin/stravahooks/actions/ActionTemplatesTest.kt`
- `src/test/kotlin/stravahooks/storage/DataStoreTest.kt`
- `src/test/kotlin/stravahooks/strava/FakeStravaApi.kt`
- `src/test/kotlin/stravahooks/telegram/FakeNotificationSender.kt`
- `src/test/kotlin/stravahooks/telegram/BotActionsTest.kt`
- `src/test/kotlin/stravahooks/polling/ActivityPollerTest.kt`

**Modified**:
- `ActionRunner.kt` — validateSyntax, instruction limit via custom ContextFactory
- `ActionEngine.kt` — validateSyntax delegation, validateChanges
- `StravaClient.kt` — implements StravaApi interface
- `StravaHooksBot.kt` — delegates to BotActions, adds onboarding/templates/description UI
- `ActivityPoller.kt` — StravaApi injection, NotificationSender, edit cap
- `StravaHooksConfig.kt` — maxScriptInstructions, maxPollEditsPerCycle
- `DataStore.kt` — PendingActionEdit.field extension
- `Main.kt` — wire new dependencies

## Verification

After each step:
1. `./gradlew test` — all tests pass
2. `./gradlew installDist` — builds successfully

After all steps:
1. `./dev-rebuild.sh` — deploy to Docker
2. Manual smoke test in Telegram (~19 min total):
   - `/start` as new user → intro message
   - `/help` → command list
   - Link Strava → OAuth flow
   - Create action from template → preview on activity
   - Create action from scratch with bad syntax → rejection + retry
   - Create action that writes readonly field → error in preview
   - Edit action description
   - Enable action, wait for poll → receive notification
   - Verify `while(true)` script is killed by instruction limit

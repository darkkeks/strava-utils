# Code Review: strava-utils (Full Codebase)

**Date**: 2026-03-21
**Reviewer**: Kotlin Code Reviewer (AI)
**Scope**: All production and test sources — `Main.kt`, `actions/`, `config/`, `oauth/`, `polling/`, `storage/`, `strava/`, `telegram/`, `build.gradle.kts`

---

## Executive Summary

This is a well-structured, reasonably idiomatic Kotlin application that polls Strava for new activities and runs user-defined JavaScript actions against them via a Telegram bot interface. The architecture is clean: a `DataStore` (JSON file), a `StravaClient` (HTTP), an `ActionRunner` (Rhino JS), `BotActions` (domain logic), and `StravaHooksBot` (presentation/dispatch). Test coverage is notably good for the domain layer. The most significant concerns are: (1) the flat-JSON persistence strategy creates a data-loss race condition under concurrent writes; (2) `runBlocking` is used pervasively in a Ktor-coroutine context, which defeats the concurrency model and can cause thread starvation; (3) `StravaHooksBot` is a 1 000-line God class that handles parsing, business logic, and UI formatting all in one file; (4) access tokens and refresh tokens are stored in a plain JSON file with no encryption or permissions hardening; (5) several logic bugs exist in the polling loop and Telegram callback handler.

---

## Critical Issues

### C1 — Data-loss race between `DataStore.load()` and `DataStore.save()` (every `@Synchronized` method)

**File**: `src/main/kotlin/stravahooks/storage/DataStore.kt` — every method

Each `@Synchronized` method on `DataStore` independently acquires the JVM intrinsic lock on `this`, calls `load()`, mutates, calls `save()`. The lock covers only one call frame at a time. Consider the sequence:

```
Thread A: load()          -> acquires lock, reads state, releases lock
Thread B: load()          -> acquires lock, reads same state, releases lock
Thread A: save(stateA)    -> acquires lock, writes, releases lock
Thread B: save(stateB)    -> acquires lock, OVERWRITES stateA with stale data
```

`upsertUser`, `putOAuthState`, `appendApplyLog`, etc., all do a load-modify-save as **three separate synchronized blocks**. The sequence above is possible when the activity poller thread and the Telegram bot thread operate concurrently — which they always do.

**Fix**: Hold the lock across the full read-modify-write triple. Extract a `private fun <T> withState(block: (StoredState) -> Pair<StoredState, T>): T` that holds `@Synchronized` once:

```kotlin
@Synchronized
private fun <T> withState(block: (StoredState) -> Pair<StoredState, T>): T {
    val state = loadInternal()
    val (newState, result) = block(state)
    saveInternal(newState)
    return result
}
```

All public methods then delegate to `withState` instead of calling the public `load()`/`save()` themselves.

---

### C2 — `pollOnce()` uses `return@forEach` where it should use `continue`, silently skips the `lastPolledAt` update on error paths

**File**: `src/main/kotlin/stravahooks/polling/ActivityPoller.kt`, lines 73 and 99

When `fetchActivity` returns null (line 73) the inner `return@forEach` exits the *outer* `ordered.forEach` lambda, not just the current iteration. The same happens on action errors (line 99) and validation errors (line 119). As a result:

1. When any activity fetch fails, all *subsequent* activities for that user in the same poll cycle are silently skipped.
2. The `maxSeen` bookmark is not updated for users who hit these error paths even though some activities may have been processed successfully.

**Reproduction**:
- User has 3 recent activities: ids 1, 2, 3.
- `fetchActivity(2)` returns null (e.g., a 403 or transient error).
- Activities 2 and 3 are not processed; `lastPolledAt` is only updated to the timestamp of activity 1.

**Fix**: Replace `return@forEach` (exits outer loop) with a labeled `continue` equivalent — restructure the body as a helper function that returns early, or use a `for` loop with `continue`:

```kotlin
for (meta in ordered) {
    maxSeen = max(maxSeen, meta.startEpoch)
    val activity = stravaApi.fetchActivity(account.accessToken, meta.id) ?: continue
    // ... process activity, use continue on error paths
}
```

---

### C3 — Hardcoded scope string duplicated and inconsistent

**File**: `src/main/kotlin/stravahooks/oauth/StravaOAuthServer.kt` line 40, `src/main/kotlin/stravahooks/telegram/StravaHooksBot.kt` lines 544, 952

The scope string `"read,activity:read_all,activity:write"` is defined three times: once as `defaultScopes` in `StravaOAuthServer`, once as the `REQUIRED_SCOPES` set in `StravaHooksBot.Companion`, and once as a string literal in `linkText()`. The `StravaOAuthServer.defaultScopes` is used as a fallback when the token response has no scope, meaning stored accounts can end up with a fallback scope string that differs from `REQUIRED_SCOPES.joinToString(",")` depending on order. The fallback in `persistTokens` (line 166) uses `defaultScopes` which is a comma-separated string with no spaces; `hasFullScope` splits on `","` after `trim()`, so this works today but is fragile.

**Fix**: Extract a single `REQUIRED_SCOPES` constant (as an ordered list to preserve canonical form) in a shared location (e.g., a `StravaScopes` object in the `strava` package), and use it everywhere.

---

## Major Recommendations

### M1 — `runBlocking` throughout `StravaClient` blocks the calling thread inside a coroutine context

**File**: `src/main/kotlin/stravahooks/strava/StravaClient.kt`, every method

Every API call wraps Ktor's `suspend` functions with `runBlocking`. When `StravaClient` is called from the Ktor server's coroutine (e.g., from `StravaOAuthServer.exchangeCode`) or from `ActivityPoller` (which runs on its own OS thread but will eventually be called from a coroutine context if the codebase evolves), `runBlocking` inside a coroutine dispatcher blocks a thread, which is a known anti-pattern that can cause deadlocks or thread starvation under load.

The `StravaApi` interface should be a `suspend` interface:

```kotlin
// Before
interface StravaApi {
    fun fetchActivity(accessToken: String, activityId: Long): StravaActivity?
    fun updateActivity(accessToken: String, activityId: Long, body: Map<String, Any?>): UpdateResult
    // ...
}

// After
interface StravaApi {
    suspend fun fetchActivity(accessToken: String, activityId: Long): StravaActivity?
    suspend fun updateActivity(accessToken: String, activityId: Long, body: Map<String, Any?>): UpdateResult
    // ...
}
```

`StravaClient` methods then become `suspend` functions that call Ktor's client directly without `runBlocking`. Callers that are not yet coroutines (`ActivityPoller.pollOnce()`) can use `runBlocking { ... }` at the *call site* as a bridging layer until they are converted to coroutines, which is the correct pattern.

---

### M2 — `StravaHooksBot` is a 1 000-line God class

**File**: `src/main/kotlin/stravahooks/telegram/StravaHooksBot.kt`

`StravaHooksBot` mixes Telegram dispatch, UI-string assembly, OAuth URL construction, athlete name caching, and scope logic in a single class. It also duplicates `refreshAccountIfNeeded` logic that already exists in `BotActions`. This makes the class hard to test (the entire Telegram API interaction cannot be mocked at a unit-test level) and hard to reason about.

**Recommended decomposition**:
- Extract a `BotPresenter` or set of `*Formatter` functions responsible for turning domain results into Telegram `SendMessage`/`EditMessageText` objects.
- Move `buildAuthorizeUrl`, `linkText`, `accountStatus` into `BotActions` or a dedicated `AuthPresenter`.
- Move `scopeWarnings`, `scopeSummary`, `hasFullScope` into a `ScopeChecker` utility.
- The `handleCallback` method's `else` branch contains a 60+ branch `if/else if` chain. Convert it to a `when` expression or a command-dispatch `Map<String, (CallbackContext) -> Unit>`.

---

### M3 — `DataStore` reads and writes the entire JSON file on every operation — O(n) I/O for every log append

**File**: `src/main/kotlin/stravahooks/storage/DataStore.kt`

Every `appendApplyLog` call reads the entire file, deserializes the whole `StoredState`, appends one entry, re-serializes everything, and writes the entire file back. In a multi-user deployment with an active polling cycle (5-minute intervals, each cycle processing up to 10 edits per user, each generating a log entry) this creates `O(users * activities)` full file I/O per cycle. With 200 log entries capped, the serialized file also grows unboundedly in the `users` and `oauthStates` sections.

For the current personal-use scale this is acceptable, but it is worth noting that switching to SQLite (via `xerial/sqlite-jdbc` or `Exposed`) would eliminate this concern entirely and solve the race condition from C1 simultaneously. If staying with JSON, at minimum keep the log in a separate append-only file.

---

### M4 — Token refresh in `BotActions.refreshAccountIfNeeded` is duplicated in `ActivityPoller.refreshAccountIfNeeded`

**Files**: `src/main/kotlin/stravahooks/telegram/BotActions.kt` lines 400-424, `src/main/kotlin/stravahooks/polling/ActivityPoller.kt` lines 185-209

Both classes contain an almost identical 25-line function for refreshing Strava tokens. They differ only in that `BotActions.refreshAccountIfNeeded` is `public` while `ActivityPoller`'s is `private`, and both call `dataStore.upsertUser` to persist the refreshed token. Any change to the refresh logic must be applied in two places.

**Fix**: Move `refreshAccountIfNeeded` into `BotActions` (it is already there) and have `ActivityPoller` accept or use a `BotActions` instance (or extract the token-refresh concern into a standalone `TokenRefresher` class that both can depend on).

---

### M5 — The `handleCallback` `else` branch is a 60-branch `if/else if` chain

**File**: `src/main/kotlin/stravahooks/telegram/StravaHooksBot.kt`, lines 115-323

This style is error-prone (fall-through risk if a `return` or `editCallbackMessage` is accidentally omitted), hard to navigate, and hard to test. Convert it to a `when` expression — Kotlin's `when` can use `startsWith` predicates:

```kotlin
when {
    data == "menu"                         -> handleMenuCallback(callback)
    data == "logout"                       -> handleLogoutCallback(callback)
    data.startsWith("action_show:")        -> handleActionShowCallback(callback, data.removePrefix("action_show:"))
    data.startsWith("action_toggle:")      -> handleActionToggleCallback(callback, data.removePrefix("action_toggle:"))
    // ...
}
```

Each branch becomes a small private function, making the logic independently testable.

---

### M6 — `println` used for error logging in production code

**File**: `src/main/kotlin/stravahooks/telegram/StravaHooksBot.kt`, lines 73, 99, 215, 233, 333, 440

`println` bypasses Logback, so these messages never appear in production log files and cannot be correlated with structured log aggregation. Replace all `println(...)` in `StravaHooksBot` with `logger.warn(...)` or `logger.error(...)`.

```kotlin
// Before
} catch (e: TelegramApiException) {
    println("Failed to send message: ${e.message}")
}

// After
} catch (e: TelegramApiException) {
    logger.warn("Failed to send message to $chatId", e)
}
```

---

### M7 — `OAuthStateEntry.telegramUserId` is nullable but `consumeOAuthState` returns `Long?` — ambiguity between "state not found" and "state was anonymous"

**File**: `src/main/kotlin/stravahooks/storage/DataStore.kt` lines 109-113, `src/main/kotlin/stravahooks/storage/DataStore.kt` line 68

`OAuthStateEntry.telegramUserId` is `Long?`, allowing an anonymous OAuth flow. `consumeOAuthState` returns `Long?` where `null` means either "nonce not found / expired" or "nonce found but telegramUserId was null". The caller in `StravaOAuthServer` treats `null` as "invalid or expired" (line 78), which would silently accept an anonymous state and then crash on `persistTokens` when `telegramUserId` is required.

**Fix**: Return a sealed result:
```kotlin
sealed class ConsumeResult {
    object NotFound : ConsumeResult()
    data class Found(val telegramUserId: Long?) : ConsumeResult()
}
```

Or, if anonymous flows are not needed, make `OAuthStateEntry.telegramUserId` non-nullable.

---

### M8 — `StravaHooksBot` calls `runBlocking` inside the long-polling callback thread

**File**: `src/main/kotlin/stravahooks/telegram/StravaHooksBot.kt`, `refreshAthleteName` method (line 990)

`runBlocking` is called inside `refreshAthleteName`, which is called from the Telegram long-polling callback thread (`LongPollingSingleThreadUpdateConsumer.consume`). This blocks the single Telegram update-processing thread for the duration of a network call to the Strava API, delaying all subsequent Telegram messages.

**Fix**: Cache the athlete name eagerly on link/refresh, or move the refresh to a background thread. The athlete name refresh on every `/status` call is unnecessary; a periodic background refresh is sufficient.

---

## Idiomatic Kotlin Improvements

### K1 — Use `buildList` / `buildMap` instead of `mutableListOf` + `add`

**Files**: `ActionEngine.kt`, `StravaHooksBot.kt`, `ActivityPoller.kt`

Several places create a `mutableListOf()`, add items, and return. Kotlin's `buildList` / `buildMap` is cleaner:

```kotlin
// Before (ActionEngine.normalizeActivity)
val normalized = mutableMapOf<String, Any?>()
normalized["id"] = activity.id
normalized["type"] = activity.type
// ...
return normalized

// After
return buildMap {
    put("id", activity.id)
    put("type", activity.type)
    // ...
}
```

---

### K2 — `normalizeActivity` returns `MutableMap` in public API — unnecessary mutability leak

**File**: `src/main/kotlin/stravahooks/actions/ActionEngine.kt`, line 33

`normalizeActivity` returns `MutableMap<String, Any?>`. The only caller that needs to mutate the map is `ActionRunner`, which receives it as a `MutableMap<String, Any?>` parameter. Returning `MutableMap` from `normalizeActivity` forces all callers to acknowledge mutability. Consider having `normalizeActivity` return `Map<String, Any?>` and having callers that need to mutate call `.toMutableMap()` at the call site.

---

### K3 — `when` expression preferred over `if/else` chains for command routing

**File**: `src/main/kotlin/stravahooks/telegram/StravaHooksBot.kt`, lines 78-89

The `when` block already in use is good, but the `else` branch of `handleCallback` uses `if/else if`. See M5.

---

### K4 — `var found = false` / mutate-from-lambda pattern can be replaced with a functional approach

**File**: `src/main/kotlin/stravahooks/telegram/BotActions.kt`, `updateActionCode`, `updateActionDescription`, `toggleAction`, `deleteAction`

```kotlin
// Before
var found = false
dataStore.upsertUser(telegramUserId) { user ->
    val updated = user.actions.map { action ->
        if (action.id == id) {
            found = true
            action.copy(code = code, updatedAt = now)
        } else action
    }
    user.copy(actions = updated)
}
return found
```

The mutable outer `var` is closed over in a lambda — this is functional-style code using an imperative side-effect, which is the least idiomatic combination. The data-flow is cleaner if the lambda returns enough information:

```kotlin
fun updateActionCode(telegramUserId: Long, id: String, code: String): Boolean {
    val now = Instant.now().epochSecond
    var found = false
    dataStore.upsertUser(telegramUserId) { user ->
        val newActions = user.actions.map { action ->
            if (action.id == id) { found = true; action.copy(code = code, updatedAt = now) }
            else action
        }
        user.copy(actions = newActions)
    }
    return found
}
```

A cleaner approach is to separate the lookup from the update: first check the action exists, then upsert. This is safe because `DataStore.upsertUser` is synchronized. Alternatively, use `any { it.id == id }` before the upsert.

---

### K5 — Prefer `keys + keys` union by using `(before.keys + after.keys).toSet()`

**File**: `src/main/kotlin/stravahooks/actions/ActionEngine.kt`, line 143

```kotlin
// Before
val allKeys = before.keys + after.keys   // produces a List with potential duplicates

// After
val allKeys = before.keys + after.keys   // this is fine since forEach deduplication is not needed
                                          // but if uniqueness matters:
val allKeys = (before.keys union after.keys)
```

`before.keys + after.keys` produces a `List<String>` with duplicates when a key appears in both maps; each duplicate key triggers the `valuesEqual` check twice (they are always equal to each other on the second check, so no bug, but it is wasted work). Use `union` or `toSet()`.

---

### K6 — `activityUrl` and `buildChangeSummary` are duplicated in `BotActions` and `ActivityPoller`

**Files**: `BotActions.kt` lines 454-464, `ActivityPoller.kt` lines 211-221

Both classes define identical `buildChangeSummary` and `activityUrl` functions. Extract them to a shared utility object or top-level functions in the `stravahooks` or `stravahooks.actions` package.

---

### K7 — `prepareSource` heuristic is fragile

**File**: `src/main/kotlin/stravahooks/actions/ActionRunner.kt`, lines 90-97

```kotlin
return if (trimmed.contains("function action")) {
    trimmed
} else {
    "function action(activity) {\n$trimmed\n}\n"
}
```

The check `contains("function action")` would falsely treat code like `// example: function action demo` as a full action definition. Prefer checking for the exact pattern, e.g., a regex `Regex("""^\s*function\s+action\s*\(""")` on the first non-comment line, or simply always wrapping and letting Rhino report if a function named `action` is defined.

---

### K8 — `refreshAthleteName` uses `runBlocking` where a simpler non-suspending cached call would suffice

**File**: `src/main/kotlin/stravahooks/telegram/StravaHooksBot.kt`, lines 989-1002

See M8. Additionally, the method calls `stravaApi.fetchAthleteName` directly even though `StravaApi.fetchAthleteName` is already designed to call `fetchAthleteName` on the account's access token. The pattern of calling `refreshAccountIfNeeded` then calling `fetchAthleteName` is fine semantically, but the `runBlocking` wrapping a `suspend fun` that itself calls `runBlocking` inside `StravaClient` is doubly problematic.

---

### K9 — Callback data IDs can exceed Telegram's 64-byte limit

**File**: `src/main/kotlin/stravahooks/telegram/StravaHooksBot.kt`, multiple locations

Telegram inline keyboard `callbackData` is limited to 64 bytes. The current patterns like `"apply_preview_action:$activityId:$actionId"` (e.g., `"apply_preview_action:12345678901:a1b2c3d4"` = ~40 bytes) are currently safe, but `"action_from_template:${template.id}"` and `"action_show:${action.id}"` where `action.id` is an 8-char UUID substring are also within range. This is acceptable now but should be documented as a constraint so future extensions do not inadvertently exceed the limit.

---

### K10 — `toChangePairsFromSummary` is an inverse-parse of a human-readable string — fragile serialization

**File**: `src/main/kotlin/stravahooks/actions/ActionEngine.kt`, lines 99-112

`PendingApply` stores the change summary as a pre-formatted human-readable `String` and then `toChangePairsFromSummary` parses it back. This is fragile: if a field value contains `: ` or `->` the parser will produce wrong results. Example: if an activity name is `"Sprint: Before -> After"`, the summary line becomes `"name: Sprint: Before -> After -> Sprint: Before -> After"` and the parser will split on the first `->`.

**Fix**: Store `changes: Map<String, ChangePair>` directly in `PendingApply` (alongside `summary: String` for display) instead of reconstructing it from the display string. `PendingApply` already has room to add the field and `DataStore` handles Jackson serialization.

---

## Security Findings

### S1 — Strava access tokens and refresh tokens stored in plaintext JSON

**File**: `src/main/kotlin/stravahooks/storage/DataStore.kt` (`StravaAccount` in `StoredState`)

The data file (path configured as `data_path`) contains plaintext `access_token` and `refresh_token` for every linked user. If the file is accessible to other processes on the host, tokens can be exfiltrated. In Docker the file path is typically mounted as a volume with `0644` or `0777` permissions.

**Mitigations** (choose based on deployment context):
- Set file permissions to `0600` via `Files.setPosixFilePermissions` after every write.
- Use OS keychain or a secrets manager (not practical for a small personal bot, but document the risk).
- At minimum, add a note in the README and the stub config that `data_path` should be on a permissions-restricted directory.

---

### S2 — No rate limiting on the Ktor OAuth callback endpoint

**File**: `src/main/kotlin/stravahooks/oauth/StravaOAuthServer.kt`

The `/strava/oauth/callback` endpoint is publicly accessible. An attacker who can enumerate valid nonces (24 URL-safe base64 characters — infeasible to brute-force, but the state TTL is 10 minutes so the window is narrow) could call the endpoint. The nonce is cryptographically random via `SecureRandom`, so the actual risk is low. However, there is no rate limiting, logging of client IP, or HTTPS enforcement.

**Recommendation**: Add a `RateLimit` plugin (Ktor has `ktor-server-rate-limit` since 2.3) and log the client IP for diagnostics.

---

### S3 — `htmlEscape` does not escape `"` — insufficient for attribute contexts

**File**: `src/main/kotlin/stravahooks/telegram/StravaHooksBot.kt`, lines 829-834

```kotlin
private fun htmlEscape(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
```

The function does not escape `"` (`&quot;`) or `'` (`&#39;`). For the current usage (inside `<pre><code>` blocks and inline text in Telegram HTML mode) this is safe because the content is never placed in an HTML attribute. However, it is a subtle footgun if the function is reused for attribute contexts. Either document the limitation or add the missing escapes.

---

### S4 — `client_secret` logged to warn output on token exchange failure

**File**: `src/main/kotlin/stravahooks/oauth/StravaOAuthServer.kt`, lines 119-128

`redactTokenBody` redacts `access_token` and `refresh_token` but not `client_secret`. The Strava token exchange request body includes `client_secret`. If the request body is echoed in an error response (which Strava does not do, but a mis-configured proxy might), the `client_secret` could end up in logs. The `redactTokenBody` function should also redact `client_secret`.

---

### S5 — Rhino `JavaScript` execution — sandbox escape risk is low but should be documented

**File**: `src/main/kotlin/stravahooks/actions/ActionRunner.kt`

User-supplied JavaScript is executed inside Rhino. The sandbox is:
- `instructionObserverThreshold = 10_000` (fires every 10k instructions)
- `maxInstructions = 100_000` (terminates after 100k instructions total)
- Only `console` and `activity` objects are exposed in scope.

Standard Java classes are accessible via `java.lang.*` in Rhino unless explicitly disabled. For example, `java.lang.System.exit(0)` would terminate the JVM. The `Context.setOptimizationLevel(-1)` is not set but also not required for instruction counting.

**Fix**: Call `context.setClassShutter(ClassShutter { false })` in `makeContext()` to prevent access to any Java class from JS:

```kotlin
override fun makeContext(): Context {
    val cx = super.makeContext()
    cx.instructionObserverThreshold = 10_000
    cx.setClassShutter { _ -> false }  // block all Java class access
    return cx
}
```

This is a meaningful security improvement for any deployment where users are not fully trusted.

---

## Dependency Recommendations

### D1 — Build is missing a version catalog (`libs.versions.toml`)

**File**: `build.gradle.kts`

All dependency versions are hardcoded directly in `build.gradle.kts`. As the dependency list grows, a `libs.versions.toml` version catalog improves readability and IDE support:

```toml
# gradle/libs.versions.toml
[versions]
ktor = "3.3.0"
jackson = "2.21.0"
logback = "1.5.18"
rhino = "1.7.14"
telegram = "9.2.1"
junit = "5.13.4"

[libraries]
ktor-server-core = { module = "io.ktor:ktor-server-core", version.ref = "ktor" }
# ...
```

---

### D2 — `kotlin("jvm") version "2.3.10"` does not exist yet as of the knowledge cutoff

**File**: `build.gradle.kts`, line 2

The Kotlin JVM plugin version `2.3.10` is declared. As of the assistant's knowledge cutoff (August 2025), the latest Kotlin release was in the 2.0.x / 2.1.x range. This suggests the version number may be aspirational or a typo; verify the published version on the Kotlin GitHub releases page and use the correct pinned version to ensure reproducible builds.

---

### D3 — `ktor-serialization-jackson` adds Jackson to a project that could use `kotlinx.serialization`

**File**: `build.gradle.kts`, line 21

The project uses Jackson both for the Ktor content-negotiation plugin (`ktor-serialization-jackson`) and directly for `ConfigLoader` and `DataStore`. Jackson is a large dependency that requires `@JsonProperty` annotations and a separate Kotlin module. If this were a greenfield project, `kotlinx.serialization` would be more idiomatic (it is first-party, null-safe by design, and has a smaller footprint). For an existing codebase the migration cost may not be worth it, but it is worth noting.

---

### D4 — `org.telegram:telegrambots-client` creates a second `OkHttpTelegramClient` in `StravaOAuthServer`

**File**: `src/main/kotlin/stravahooks/oauth/StravaOAuthServer.kt`, line 138

`notifyLinked` creates a new `OkHttpTelegramClient(config.telegramBotToken)` on every invocation — i.e., a new `OkHttpClient` instance is created for each OAuth callback. OkHttp clients hold thread pools and connection pools. The client should be created once (at construction time of `StravaOAuthServer`) and reused, or the `TelegramNotificationSender` (which already holds a shared `OkHttpTelegramClient`) should be injected into `StravaOAuthServer`:

```kotlin
class StravaOAuthServer(
    private val config: StravaHooksConfig,
    private val dataStore: DataStore,
    private val oauthStateStore: OAuthStateStore,
    private val notificationSender: NotificationSender  // inject instead of creating
)
```

---

### D5 — No static analysis (Detekt) or code formatting (ktlint) configured

**File**: `build.gradle.kts`

There are no Detekt or ktlint tasks configured. Adding Detekt would catch several of the issues in this review automatically on CI. Suggested minimal additions:

```kotlin
plugins {
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
}
detekt {
    buildUponDefaultConfig = true
}
```

---

## Minor Improvements

### N1 — `ConfigLoader` uses `Paths.get()` when `Path.of()` is available

**File**: `src/main/kotlin/stravahooks/config/ConfigLoader.kt`, line 19

`Paths.get(...)` is the Java 7 API; `Path.of(...)` is the Java 11 idiomatic equivalent. The rest of the codebase consistently uses `Path.of(...)`. Unify to `Path.of(...)`.

---

### N2 — `StravaActivity.id` is nullable but used unsafely in many places

**File**: `src/main/kotlin/stravahooks/strava/StravaModels.kt`, line 9

`StravaActivity.id` is `Long?`, but in practice every activity returned by the API has an id. Making it non-nullable would remove a significant source of `.let { }` / null-checks throughout the codebase. If keeping it nullable for deserialization safety, the `normalizeActivity` function should document the contract that it only receives activities with a non-null id.

---

### N3 — `ActionEngine.WRITABLE_FIELDS` is a `Set<String>` but `buildUpdate` and `buildUpdateBody` do string-keyed field lookups — consider a typed enum

**File**: `src/main/kotlin/stravahooks/actions/ActionEngine.kt`

`WRITABLE_FIELDS` is `setOf("name", "description", "commute", "trainer", "mute", "gear_id")`. All field names appear as string literals in multiple places (ActivityUpdate constructor, buildUpdateBody, validateChanges). A typo (e.g., `"gear_Id"`) would silently produce a no-op. A simple enum or sealed class for writable field names would add compile-time safety.

---

### N4 — `PendingActionCreate.stage` should be an enum, not a `String`

**File**: `src/main/kotlin/stravahooks/storage/DataStore.kt`, line 148

```kotlin
data class PendingActionCreate(
    val startedAt: Long,
    val stage: String,  // "name" or "code"
    val name: String? = null
)
```

The `stage` field has exactly two valid values (`"name"` and `"code"`). An `enum class Stage { NAME, CODE }` would make exhaustive `when` checks possible and prevent invalid stage values.

---

### N5 — `ApplyLogEntry.mode` and `ApplyLogEntry.result` should be enums

**File**: `src/main/kotlin/stravahooks/storage/DataStore.kt`, lines 170-187

`mode` takes values `"poll"`, `"preview"`, `"apply"`. `result` takes values `"applied"`, `"failed"`, `"error"`, `"no_changes"`, `"preview_ready"`. Replacing these with `enum class` types would:
- prevent typos (there is already a subtle inconsistency: `BotActions` uses `"preview"` for mode but `ActivityPoller` uses `"poll"`)
- make exhaustive `when` branches compile-checked

---

### N6 — Unnecessary `@Synchronized` on `DataStore.load()` and `DataStore.save()` when used only internally

**File**: `src/main/kotlin/stravahooks/storage/DataStore.kt`

Both `load()` and `save()` are `@Synchronized` public methods, but their main purpose is as building blocks for other `@Synchronized` methods in the same class. A re-entrant call from within a synchronized method will succeed (JVM intrinsic locks are reentrant), so there is no deadlock, but the annotation signals "this is safe to call from multiple threads" which is misleading for the reasons described in C1. Consider making `load()` and `save()` `private` (with non-synchronized private helpers) and only exposing the compound operations.

---

### N7 — `ActivityPoller.start()` uses a raw `Thread` with `Thread.sleep` — not coroutines-aware

**File**: `src/main/kotlin/stravahooks/polling/ActivityPoller.kt`, lines 29-39

```kotlin
thread(name = "activity-poller", isDaemon = true) {
    while (true) {
        try { pollOnce() } catch (e: Exception) { ... }
        Thread.sleep(intervalSeconds * 1000)
    }
}
```

`Thread.sleep` is not interruptible via coroutine cancellation and cannot be cleanly shut down. For a long-running daemon service this is acceptable, but if graceful shutdown is ever needed, this thread cannot be cancelled without `Thread.interrupt()`. Consider replacing with a `CoroutineScope` + `delay(...)` so cancellation is cooperative.

---

### N8 — `buildChangeSummary` does not distinguish `null` values from the string `"null"`

**File**: `src/main/kotlin/stravahooks/actions/ActionEngine.kt` and `BotActions.kt`

```kotlin
val before = value.first?.toString() ?: "null"
```

If a field's value is the literal string `"null"`, the output is indistinguishable from a null value. This affects `toChangePairsFromSummary` parsing (K10) and the human-readable log entries. A minor fix is to use `"<null>"` as the null sentinel.

---

### N9 — Test class-level `tmpDir` is shared across tests within a class — potential test pollution

**Files**: `src/test/kotlin/stravahooks/polling/ActivityPollerTest.kt` line 14, `src/test/kotlin/stravahooks/telegram/BotActionsTest.kt` line 17

```kotlin
private val tmpDir = Files.createTempDirectory("poller-test")
private val dataStore = DataStore(tmpDir.resolve("data.json"))
private val fakeApi = FakeStravaApi()
```

All test methods within `ActivityPollerTest` share the same `dataStore` and `fakeApi` instances. State accumulated by one test (entries in `fakeApi.summaries`, `fakeApi.updateCalls`, etc.) can bleed into subsequent tests if a test does not clean up. JUnit 5's default lifecycle is `PER_METHOD` (a new instance per test), but since `tmpDir`, `dataStore`, and `fakeApi` are initialized in the class body (not in a `@BeforeEach`), they are re-initialized for each test method because Kotlin `val` in class body initializers run per instance construction. This is fine for JUnit 5 `PER_METHOD` but is worth being explicit about: annotate with `@TestInstance(TestInstance.Lifecycle.PER_METHOD)` or add a `@BeforeEach` for clarity.

---

### N10 — `StravaOAuthServer.exchangeCode` creates a new `jacksonObjectMapper()` on every invocation

**File**: `src/main/kotlin/stravahooks/oauth/StravaOAuthServer.kt`, line 124

```kotlin
val token: TokenResponse = jacksonObjectMapper().readValue(raw)
```

`ObjectMapper` instances are thread-safe after configuration; creating one per request is wasteful. Use a companion/class-level `val mapper = jacksonObjectMapper()` as `ConfigLoader` and `DataStore` already do.

---

## Action Items Summary

### Critical
- [ ] [CRITICAL] **C1** — Fix data-loss race in `DataStore`: hold the lock across the entire load-modify-save sequence. _(deferred: will be resolved by SQLite migration — see `docs/storage-migration.md`)_
- [x] [CRITICAL] **C2** — Fix `return@forEach` in `ActivityPoller.pollOnce()` inner loop: use `continue` semantics so a single failed `fetchActivity` does not silently abort all remaining activities for the user.
- [x] [CRITICAL] **C3** — Deduplicate the hardcoded scope string across `StravaOAuthServer`, `StravaHooksBot`, and `linkText`.

### Major
- [x] [MAJOR] **M1** — Make `StravaApi` a `suspend` interface; remove all `runBlocking` from `StravaClient`.
- [x] [MAJOR] **M2** — Decompose `StravaHooksBot` (1 000 lines) into presenter/formatter helpers and a dispatch table.
- [ ] [MAJOR] **M3** — Document or mitigate the full-file-rewrite I/O pattern in `DataStore`; consider a separate log file or SQLite. _(deferred: will be resolved by SQLite migration — see `docs/storage-migration.md`)_
- [x] [MAJOR] **M4** — Deduplicate `refreshAccountIfNeeded` from `ActivityPoller` and `BotActions`.
- [x] [MAJOR] **M5** — Replace the 60-branch `if/else if` in `handleCallback` with `when` + small handler functions.
- [x] [MAJOR] **M6** — Replace all `println` in `StravaHooksBot` with `logger.warn`/`logger.error`.
- [x] [MAJOR] **M7** — Disambiguate `consumeOAuthState` return type (`null` = not found vs. anonymous state).
- [x] [MAJOR] **M8** — Remove `runBlocking` from `refreshAthleteName` in the Telegram callback thread; use background refresh.

### Security
- [x] [SECURITY] **S5** — Add `cx.setClassShutter { _ -> false }` in `InstructionLimitContextFactory.makeContext()` to block Java class access from user JS.
- [x] [SECURITY] **S1** — Set file permissions to `0600` on `data_path` after every write; document token storage risk.
- [x] [SECURITY] **S4** — Add `client_secret` to `redactTokenBody` redaction patterns.
- [x] [SECURITY] **S3** — Add `"` and `'` escaping to `htmlEscape`, or document the attribute-context limitation.
- [ ] [SECURITY] **S2** — Add rate limiting to the Ktor OAuth callback endpoint. _(deferred: not needed for MVP)_

### Minor
- [x] [MINOR] **K1** — Replace `mutableMapOf` + imperative puts in `normalizeActivity` with `buildMap { }`.
- [x] [MINOR] **K2** — Change `normalizeActivity` return type from `MutableMap` to `Map`.
- [x] [MINOR] **K5** — Use `union` instead of `+` for key set union in `validateChanges`.
- [x] [MINOR] **K6** — Extract duplicated `buildChangeSummary` and `activityUrl` to shared utilities.
- [x] [MINOR] **K7** — Improve `prepareSource` heuristic with a regex instead of `contains`.
- [x] [MINOR] **K10** — Store `Map<String, ChangePair>` directly in `PendingApply` instead of reparsing the summary string.
- [x] [MINOR] **D1** — Add a `libs.versions.toml` version catalog.
- [ ] [MINOR] **D2** — Verify the Kotlin plugin version `2.3.10` is a valid published release. _(intentional — keeping as-is)_
- [x] [MINOR] **D4** — Inject `NotificationSender` into `StravaOAuthServer` instead of creating a new `OkHttpTelegramClient` per callback.
- [x] [MINOR] **D5** — Add Detekt and/or ktlint to the build.
- [x] [MINOR] **N1** — Replace `Paths.get()` with `Path.of()` in `ConfigLoader`.
- [x] [MINOR] **N4** — Replace `PendingActionCreate.stage: String` with an enum.
- [x] [MINOR] **N5** — Replace `ApplyLogEntry.mode` and `result` with enums.
- [x] [MINOR] **N7** — Replace raw `thread` + `Thread.sleep` in `ActivityPoller` with a coroutine scope and `delay`.
- [x] [MINOR] **N10** — Move `jacksonObjectMapper()` in `StravaOAuthServer.exchangeCode` to a class-level `val`.

---

## What Is Done Well

- **Sealed classes for domain results** (`BotActions.PreviewResult`, `BotActions.ApplyResult`, `BotActions.PendingEditResult`) are idiomatic Kotlin and make exhaustive `when` handling possible throughout the presenter.
- **`StravaApi` interface** with `FakeStravaApi` in tests follows the dependency-inversion principle cleanly and enables fast, deterministic tests without any HTTP mocking framework.
- **`OAuthStateStore` with `SecureRandom` nonces** is a correct, cryptographically sound CSRF-state pattern for OAuth.
- **Instruction-limit sandbox** in `ActionRunner` via `InstructionLimitContextFactory` is a thoughtful protection against runaway user scripts.
- **`redactTokenBody`** shows awareness of token leakage in logs — good security hygiene (see S4 for a gap).
- **Test coverage** of the domain layer (`ActionRunnerTest`, `ActionEngineTest`, `ActionTemplatesTest`, `DataStoreTest`, `ActivityPollerTest`, `BotActionsTest`) is thorough and well-structured, with good use of builder helpers (`makeAction`, `seedUser`, `seedActivity`) to reduce boilerplate.
- **`ConfigLoader.writeStub`** provides a useful `init` command for new users, with an appropriate `--force` guard against accidental overwrite.
- **Logback** is correctly configured as the SLF4J backend; most logging uses the SLF4J API properly.

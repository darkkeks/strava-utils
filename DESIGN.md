# Design Overview

## Product Goal
Provide a Strava automation service similar to Strautomator, focused on safe, repeatable edits to activity metadata through user-defined scripts.

## Core User Flow
1. Sign up and connect a Strava account.
2. Define automation actions that run on new activities.
3. Optionally preview and apply actions to existing activities.
4. New uploads trigger actions automatically.

## User Interface (Telegram Bot)
- The Telegram bot is the primary UI for all workflows: signup, account linking, action setup, previews, and pipeline management.
- UX is button-first using inline keyboards; free-form text input is only used when necessary.

### Onboarding and Account Linking
1. User sends `/start`, `/help`, or any command before linking their account.
2. Bot explains the service and offers a “Link Strava account” button.
3. Button opens a Strava OAuth link that includes the Telegram user ID.
4. User grants access on Strava.
5. Strava redirects to our domain with the Telegram user ID and access token.
6. Bot confirms the link and prompts the user to create their first action.

### OAuth Scope Options
- The OAuth flow must allow selecting scopes: public read, private read, and write access.
- Users can link with read-only scopes to preview actions without modifying data.
- Bot messaging should reflect the current scope level and offer an upgrade path to write access.

## Actions and Scripting
- Actions are pure functions: input is an activity description; output is a proposed change set.
- Users configure actions using Starlark (via `starlark-pyo3`) for rich, safe customization.
- Each action can be enabled/disabled and ordered in a pipeline.

### Action Dialog and Management
- The action dialog lists configured actions and offers a “Create action” button.
- Selecting an action shows its name, description, code, and options, with buttons to:
  - edit details or code
  - run on older activities
  - enable/disable
  - delete
 - Actions execute in the order they are created; reordering is not supported in v1.
 - Users should merge actions or use conditions in code if ordering matters.

### Creating Actions
- Provide templates that demonstrate API usage:
  - edit description based on conditions and activity fields
  - toggle mute status
  - choose equipment/gear
- If a template is chosen, create the action with default name and code.
- “Create from scratch” asks the user to send Starlark code directly.
- Newly created actions start disabled by default.

### Validation
- Every time code is submitted, validate it by parsing in the Starlark interpreter.
- For now, only syntax validation is required.
- Future: optional validation by running against example or user activities.

### Action Data Model
- `id`: unique identifier.
- `name`: short, user-facing label.
- `description`: optional summary shown in the action detail view.
- `code`: Starlark source for the action.
- `enabled`: boolean, default `false` on creation.
- `order`: integer for pipeline ordering.
- `created_at` / `updated_at`: timestamps for audit/history views.

## Preview and Apply Flow for Existing Activities
- Entry point from the action detail view: “Run on older activities”.
- User chooses scope:
  - select activities one-by-one from a list, or
  - select a time range for bulk preview.
- Bot runs the action(s) in preview mode and shows proposed changes.
- For lists, each activity preview includes an “Apply” button and a “Skip” button.
- For bulk ranges, show a summary of proposed changes and require confirmation before applying.
 - Each activity preview shows:
   - activity name (current and previous if it changes)
   - key fields such as distance and time (type-specific fields may be added)
   - a change list with before/after values per field
 - Bulk summary includes:
   - count of activities with changes
   - count of activities in range with no changes
   - button to review changed activities one-by-one
 - After confirming the summary, present:
   - “Apply all” to apply every change immediately
   - “Apply step by step” to review each activity with options to:
     - apply this activity
     - skip this activity
     - apply all remaining changes

## New Activity Processing
- Newly uploaded activities trigger the pipeline automatically.
- Actions execute in configured order; each action sees the latest activity state.
- If an action proposes changes, they are applied before the next action runs.
- Users can pause or disable actions to stop automatic edits.
- Detection supports:
  - webhooks in production for reliable multi-user scale
  - polling in development for simpler setup

## Activity and Change-Set Model
- Activity fields used for actions and previews include:
  - `id`, `type`, `name`, `start_time`
  - `distance`, `moving_time`, `elapsed_time`
  - `description`, `commute`, `trainer`, `mute`
  - `gear_id` (or equipment identifiers)
- Change-set is a list of field-level edits with before/after values.
- Changes are applied only to fields explicitly set by actions.
- Strava activity updates are done via `PUT /api/v3/activities/{id}` and accept
  the `UpdatableActivity` fields (e.g., `name`, `description`, `commute`,
  `trainer`, `mute`, `gear_id`).
- Update scope requirements:
  - `activity:write` for updates
  - `activity:read_all` to update “Only Me” activities
- List activities: `GET /api/v3/athlete/activities` with `before`, `after`,
  `page`, `per_page`.
- Fetch single activity: `GET /api/v3/activities/{id}` (requires read scope).
- Gear updates: `gear_id` can be set to `'none'` to clear gear.

## Starlark Action API (v1)
- Actions are pure functions: `def action(activity): ...` mutate the activity in place and return `None`.
- The API uses only scalar fields (no lists or dicts) to simplify diffs and previews.
- Missing values are `None`; actions should handle `None` safely.
- Inject the activity as a mutable struct-like object and diff before/after.

### Activity Fields (read-only)
- `id`: int
- `name`: string
- `type`: string (sport type)
- `start_time`: string (RFC 3339 timestamp)
- `distance_m`: float
- `moving_time_s`: int
- `elapsed_time_s`: int
- `description`: string or `None`
- `commute`: bool
- `trainer`: bool
- `mute`: bool
- `visibility`: string (`public`, `followers`, `private`)
- `gear_id`: string or `None`
- `gear_name`: string or `None`
- `elevation_gain_m`: float or `None`
- `average_speed_mps`: float or `None`
- `max_speed_mps`: float or `None`
- `average_hr_bpm`: float or `None`
- `max_hr_bpm`: float or `None`
- `average_cadence_rpm`: float or `None`

### Change Set (write-only)
- Activity fields are mutable; the change set is derived from the diff between
  input and output activity objects.
- Only scalar field changes are detected; unchanged fields are ignored.

### Example Action (Starlark)
```python
def action(activity):
    if activity.type != "Ride":
        return
    if activity.distance_m is None or activity.distance_m >= 10000:
        return

    activity.mute = True
    activity.commute = True

    if activity.name:
        activity.name = f"{activity.name} (mute)"
    else:
        activity.name = "(mute)"
```

## Action Templates (Initial Catalog)
- Template: "Short ride mute"
  - Purpose: mute short rides and mark as commute.
  - Based on the example above.
- Template: "Condition-based description"
  - Example behavior: if `activity.type == "Run"` and `distance_m >= 5000`,
    append `" (long run)"` to `activity.description`.
- Template: "Gear selection"
  - Example behavior: set `activity.gear_id` based on `activity.type`
    (e.g., bike vs shoes).

## Bot Navigation and State
- Primary entry points: `/start`, `/help`, and main menu button.
- Main menu sections: Actions, Pipelines, Activity Preview, Settings.
- Each view uses inline keyboard navigation with:
  - Back to previous menu
  - Cancel to exit a flow
- Long lists (actions or activities) use pagination buttons.
- Editing flows are single-threaded per user to avoid conflicting drafts.

## Error Handling and Notifications
- Starlark parse errors are returned inline with line/column hints.
- Apply failures are reported per activity with a retry button.
- If a change cannot be applied due to missing scope, prompt to upgrade.
- API rate-limit responses trigger backoff and a user-visible delay notice.

## Permissions and Scope UX
- The bot always shows current scope level in account settings.
- If write scope is missing, actions still run in preview mode.
- Users can upgrade scope via a "Re-link Strava with write access" button.
 - If updates fail on “Only Me” activities, prompt for `activity:read_all` re-link.

## Authentication and Account Linking
- Default OAuth choice is write access, with UX safeguards to reduce accidental edits.
- OAuth link includes a Telegram user ID parameter for mapping the callback.
- Store refresh tokens and support token refresh to keep automations running.
- Account settings show link status and re-auth option if tokens expire.
- Webhook setup should verify an active subscription and create one if missing.
- Store the last received event timestamp and object id to recover missed events later.
 - OAuth uses Strava’s web authorization endpoint:
   - `GET https://www.strava.com/oauth/authorize`
   - redirect back with `code` and `state` (state echoes our Telegram user ID)
 - Exchange `code` for tokens via `POST https://www.strava.com/api/v3/oauth/token`.
 - Persist `access_token`, `refresh_token`, `expires_at`, and accepted `scope`.
- Refresh tokens with `grant_type=refresh_token` before expiration.

## Webhooks (Strava)
- Subscriptions are managed via `https://www.strava.com/api/v3/push_subscriptions`.
- Create subscription with `callback_url`, `verify_token`, `client_id`, `client_secret`.
- Strava validates the callback with a GET containing:
  - `hub.challenge`, `hub.verify_token`, `hub.mode=subscribe`.
- Respond within 2 seconds with status 200 and JSON body:
  - `{ "hub.challenge": "<value>" }`.
- Event delivery is via POST with JSON payload fields:
  - `object_type`, `object_id`, `aspect_type`, `owner_id`, `subscription_id`, `event_time`.
  - `updates` may contain multiple changed fields.
- The callback must respond 200 within 2 seconds; non-200 triggers up to 3 retries.
- View subscriptions with GET and delete via DELETE `/push_subscriptions/{id}`.

## Data Storage and Sync
- Store users, linked Strava accounts, actions, and pipelines in a JSON file.
- Use a repository-style abstraction to isolate data access and allow swapping
  to another storage backend later.
- Activity previews and change sets are cached for quick per-activity review.
- Keep a minimal audit log of applied changes (activity id, fields, timestamp).
- State is shared through the storage layer; for early versions, any queueing
  can be modeled in the same JSON storage for simplicity.
- Keep the JSON schema minimal and flexible; optimize only if needed later.

## Safety Limits
- Per-run cap on number of edited activities (configurable, default small).
- Bulk edits are limited to avoid large mass updates; design offline
  processing for larger batches later.
- Cooldown between bulk apply operations to avoid accidental mass edits.
- Maximum script size and execution time to protect the service.

## Configuration
- Provide a setup wizard that collects required credentials, domains, and
  webhook details, and writes them to a config file.
- The bot should refuse to start without a valid config.
- Config file is JSON stored in the working directory by default and can be
  overridden via CLI argument.

## Observability
- Start with structured logging for all user flows, action runs, and API errors.

## Hosting and Endpoints
- Run a single HTTP server inside Docker; HTTPS is terminated by a proxy.
- Domain is configurable and used to build OAuth and webhook URLs.
- Use separate paths for:
  - Strava OAuth callback
  - Strava webhook callback
  - future web UI

## SDK and API Usage
- Use a Telegram bot library for all bot interactions.
- Use raw Strava HTTP APIs (no Strava client library) to avoid abstraction limits.
- Preferred Telegram library: `python-telegram-bot`, supporting both polling and webhooks.

## Applying to Existing Activities
- Users can run actions on historical activities either:
  - one-by-one from a list, or
  - in bulk over a selected time range.
- A preview mode shows the proposed edits before applying.

## Automation on New Activities
- Newly uploaded activities are detected and processed automatically.
- Actions run in the configured order; each produces edits that can be applied to Strava.

## Next Areas to Detail
- Authentication and account linking.
- Activity data model and change-set schema.
- Action lifecycle (validation, preview, apply, rollback).
- UI/CLI flows for browsing and previewing changes.
- Scheduling and webhook/polling strategy for new activities.

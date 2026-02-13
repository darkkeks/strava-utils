# Repository Guidelines

## Project Structure & Module Organization
- `README.md` documents setup and runtime usage.
- `build.gradle.kts` and `settings.gradle.kts` define Gradle dependencies and targets.

## Build, Test, and Development Commands
- `stravahooks init` creates a config stub (default `stravahooks.json`).
- `stravahooks run` starts the service stub.
- `gradle run` runs the JVM CLI.
- `gradle nativeCompile` builds a GraalVM native image.

## Coding Style & Naming Conventions
- Naming: `camelCase` for functions/vars, `PascalCase` for classes, constants in `UPPER_SNAKE_CASE`.
- Use Kotlin conventions for identifiers and files.

## Testing Guidelines
- No test framework is set up yet.
- If adding tests, keep them close to the module and document the command in `README.md`.

## Commit & Pull Request Guidelines
- Commit messages follow imperative, concise summaries (e.g., “Add guided setup and config storage”).
- PRs should include: a clear description, manual test steps (commands run), and any config/schema changes.
- Link related issues when applicable; screenshots are only needed for user-visible changes.

## Security & Configuration Tips
- Local config file defaults to `stravahooks.json` in the working directory.
- Do not commit access tokens or bot tokens. Use the setup flow or environment variables for secrets.
- If changing config schema, update the setup flow and document the migration in `README.md`.

## Current Development Notes
- Docker dev flow uses host-built `installDist` mounted into the container; `./dev-rebuild.sh` runs Gradle on the host and recreates the container. The service listens on port 8080.
- `docker-compose.yml` mounts config and data at `/config`, with `STRAVAHOOKS_CONFIG=/config/stravahooks.json` and `data_path=/config/stravahooks.db.json`.
- Kotlin toolchain targets 24; Graal action execution was removed in favor of Rhino (`org.mozilla:rhino:1.7.14`).
- Action execution runs in Rhino; if code is only a body, it is wrapped in `function action(activity){ ... }`. `console.log` output is captured and shown in previews and logs.
- `ActionEngine` normalizes updates, diffs changes, and builds update payloads; apply logs store action names and change details.
- Token refresh is handled before Strava API calls; OAuth state nonces are persisted with TTL in the data store.
- The bot UI favors editing existing messages for button presses; code edit prompts are new messages so action context remains visible.
- Activity mentions should link to Strava URLs; action references should show names (not IDs).
- Polling runs in the background when enabled, applying only enabled actions and logging changes; manual apply can use disabled actions.

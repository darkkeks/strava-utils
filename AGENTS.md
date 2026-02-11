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

# Repository Guidelines

## Project Structure & Module Organization
- `src/stravahooks/cli.py` contains the CLI entry points.
- `src/stravahooks/__init__.py` defines the package.
- `README.md` documents setup and runtime usage.
- `pyproject.toml` defines dependencies and the `stravahooks` entry point.

## Build, Test, and Development Commands
- `uv venv` and `source .venv/bin/activate` create/activate a local virtualenv.
- `uv pip install -e .` installs the CLI in editable mode.
- `stravahooks init` creates a config stub (default `stravahooks.json`).
- `stravahooks run` starts the service stub.

## Coding Style & Naming Conventions
- Use Python 3.11 features (type hints, dataclasses, pathlib).
- Indentation: 4 spaces, PEP 8 layout, 88–100 char lines preferred.
- Naming: `snake_case` for functions/vars, `PascalCase` for classes, constants in `UPPER_SNAKE_CASE`.
- No formatter or linter is configured; keep style consistent with `src/stravahooks/cli.py`.

## Testing Guidelines
- No test framework is set up yet.
- If adding tests, keep them close to the module (e.g., `tests/test_cli.py`) and document the command in `README.md`.

## Commit & Pull Request Guidelines
- Commit messages follow imperative, concise summaries (e.g., “Add guided setup and config storage”).
- PRs should include: a clear description, manual test steps (commands run), and any config/schema changes.
- Link related issues when applicable; screenshots are only needed for user-visible changes.

## Security & Configuration Tips
- Local config file defaults to `stravahooks.json` in the working directory.
- Do not commit access tokens or bot tokens. Use the setup flow or environment variables for secrets.
- If changing config schema, update the setup flow and document the migration in `README.md`.

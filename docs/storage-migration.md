# Storage Migration

## Current state

Persistence is handled by `DataStore` (flat JSON file, path configured via `data_path`).
Every operation reads the whole file, mutates in-memory, and writes the whole file back.
This is acceptable for a single-user or small-group deployment but has two known issues:

1. **Race condition (C1)** — `@Synchronized` guards each individual `load()` / `save()` call
   but does not hold the lock across the full load-modify-save triple. Concurrent writes from
   the poller thread and the Telegram bot thread can silently overwrite each other.

2. **O(n) I/O (M3)** — Every `appendApplyLog` rewrites the entire file. With many users and
   frequent polls the file size grows and write latency increases proportionally.

## Planned fix

Migrate to **SQLite** via the [Exposed](https://github.com/JetBrains/Exposed) ORM or raw
`xerial/sqlite-jdbc`. SQLite solves both issues:
- Transactions eliminate the race condition with zero additional locking code.
- Append-only inserts for the apply log are O(1) instead of O(n).

## Migration notes

- Keep `DataStore` as the repository interface so callers don't change.
- Replace the JSON read/write internals with Exposed DAO/DSL or JDBC statements.
- Provide a one-time migration script that reads the existing JSON file and inserts rows.
- The Docker volume mount (`data_path`) becomes the SQLite file path.
- Jackson is still needed for config loading; Exposed does not replace it there.

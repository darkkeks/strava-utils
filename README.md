# strava-utils

Fresh Kotlin/JVM starter project.

## Requirements

- JDK 21+
- Gradle (or use the wrapper)

## Run

Start the Telegram bot (expects `stravahooks.json` in the working directory):

```bash
gradle run --args="run"
```

Create a config stub:

```bash
gradle run --args="init"
```

```bash
gradle run
```

Example with args:

```bash
gradle run --args="run --config /path/to/stravahooks.json"
```

## Telegram bot

Commands:
- `/start`, `/help` show help and a Strava linking button.
- `/link` posts the Strava OAuth link.

The HTTP callback for Strava OAuth is served at `/strava/oauth/callback` on the configured `bind_host`/`bind_port`.
Linked Strava tokens are stored in the JSON file at `data_path`.

## Build

```bash
gradle build
```

## Tests

```bash
gradle test
```

## Docker

Build the image:

```bash
docker build -t stravahooks .
```

Run the service (mount your config):

```bash
docker run --rm -p 8080:8080 -v "$PWD/stravahooks.json:/app/stravahooks.json" stravahooks
```

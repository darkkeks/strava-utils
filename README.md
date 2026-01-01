# strava-utils

A small utility to monitor a Strava profile for new activities.

## Setup (uv)

```bash
uv venv
source .venv/bin/activate
uv pip install -e .
```

## Configure

Run the guided setup to create a config file with your Strava credentials and
Telegram bot token:

```bash
strava-monitor setup
```

The setup flow will prompt for your client ID/secret and Telegram bot token.
Configuration is saved to `.strava-monitor-config.json` in the working
directory by default.

After setup, open the Telegram bot and follow the guided prompts (use the
"Send Strava access token" button or send `/token <ACCESS_TOKEN>`). The bot will
store it in the config and use it for activity monitoring.

```bash
export STRAVA_ACCESS_TOKEN="your-token"
```

## Run

```bash
strava-monitor --poll-interval 300
```

### One-shot mode

```bash
strava-monitor --once
```

### Log existing activities on first run

```bash
strava-monitor --log-existing --once
```

The script stores its last-seen activity state in `.strava-monitor-state.json` by default.

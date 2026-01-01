# strava-utils

A small utility to monitor a Strava profile for new activities.

## Setup (uv)

```bash
uv venv
source .venv/bin/activate
uv pip install -e .
```

## Configure

Run the guided setup to create a config file with your Strava credentials:

```bash
strava-monitor setup
```

The setup flow will prompt for your client ID/secret, open the Strava authorize
link, and ask you to paste the redirect URL so it can exchange the code for an
access token. Configuration is saved to
`~/.config/strava-monitor/config.json` by default.

You can still provide an access token manually:

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

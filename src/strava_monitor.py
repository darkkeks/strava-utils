from __future__ import annotations

import argparse
import json
import logging
import os
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable
from urllib.parse import parse_qs, urlparse

import httpx

STRAVA_API_BASE = "https://www.strava.com/api/v3"
DEFAULT_STATE_FILE = Path(".strava-monitor-state.json")
DEFAULT_CONFIG_FILE = Path.home() / ".config" / "strava-monitor" / "config.json"
DEFAULT_REDIRECT_URI = "http://localhost/exchange_token"
DEFAULT_SCOPES = "activity:read_all"


@dataclass
class MonitorState:
    last_activity_id: int | None = None
    last_activity_start: str | None = None


@dataclass
class MonitorConfig:
    client_id: str | None = None
    client_secret: str | None = None
    access_token: str | None = None
    refresh_token: str | None = None
    expires_at: int | None = None


@dataclass
class Activity:
    activity_id: int
    name: str
    start_date: str
    activity_type: str
    distance_m: float
    moving_time_s: int
    elapsed_time_s: int

    @classmethod
    def from_api(cls, payload: dict[str, Any]) -> "Activity":
        return cls(
            activity_id=payload["id"],
            name=payload.get("name", ""),
            start_date=payload.get("start_date"),
            activity_type=payload.get("type", ""),
            distance_m=float(payload.get("distance", 0.0)),
            moving_time_s=int(payload.get("moving_time", 0)),
            elapsed_time_s=int(payload.get("elapsed_time", 0)),
        )


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Monitor Strava for new activities and run actions.",
    )
    subparsers = parser.add_subparsers(dest="command")

    setup_parser = subparsers.add_parser(
        "setup", help="Guide setup and save Strava credentials."
    )
    setup_parser.add_argument(
        "--config-file",
        type=Path,
        default=DEFAULT_CONFIG_FILE,
        help=f"Config file path (default: {DEFAULT_CONFIG_FILE}).",
    )

    run_parser = subparsers.add_parser(
        "run", help="Run the activity monitor (default)."
    )
    run_parser.add_argument(
        "--poll-interval",
        type=int,
        default=300,
        help="Seconds between polls (default: 300).",
    )
    run_parser.add_argument(
        "--once",
        action="store_true",
        help="Run a single poll and exit.",
    )
    run_parser.add_argument(
        "--state-file",
        type=Path,
        default=DEFAULT_STATE_FILE,
        help=f"State file path (default: {DEFAULT_STATE_FILE}).",
    )
    run_parser.add_argument(
        "--log-existing",
        action="store_true",
        help="Log existing activities on first run instead of bootstrapping.",
    )
    run_parser.add_argument(
        "--per-page",
        type=int,
        default=30,
        help="Number of activities to fetch per poll (default: 30).",
    )
    run_parser.add_argument(
        "--access-token",
        default=os.getenv("STRAVA_ACCESS_TOKEN"),
        help="Strava access token (or set STRAVA_ACCESS_TOKEN).",
    )
    run_parser.add_argument(
        "--config-file",
        type=Path,
        default=DEFAULT_CONFIG_FILE,
        help=f"Config file path (default: {DEFAULT_CONFIG_FILE}).",
    )

    args = parser.parse_args(argv)
    if args.command is None:
        args.command = "run"
    return args


def load_state(path: Path) -> MonitorState:
    if not path.exists():
        return MonitorState()
    with path.open("r", encoding="utf-8") as handle:
        payload = json.load(handle)
    return MonitorState(
        last_activity_id=payload.get("last_activity_id"),
        last_activity_start=payload.get("last_activity_start"),
    )


def save_state(path: Path, state: MonitorState) -> None:
    payload = {
        "last_activity_id": state.last_activity_id,
        "last_activity_start": state.last_activity_start,
    }
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")


def load_config(path: Path) -> MonitorConfig:
    if not path.exists():
        return MonitorConfig()
    with path.open("r", encoding="utf-8") as handle:
        payload = json.load(handle)
    return MonitorConfig(
        client_id=payload.get("client_id"),
        client_secret=payload.get("client_secret"),
        access_token=payload.get("access_token"),
        refresh_token=payload.get("refresh_token"),
        expires_at=payload.get("expires_at"),
    )


def save_config(path: Path, config: MonitorConfig) -> None:
    payload = {
        "client_id": config.client_id,
        "client_secret": config.client_secret,
        "access_token": config.access_token,
        "refresh_token": config.refresh_token,
        "expires_at": config.expires_at,
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")


def fetch_activities(client: httpx.Client, per_page: int) -> list[Activity]:
    response = client.get(
        f"{STRAVA_API_BASE}/athlete/activities",
        params={"per_page": per_page, "page": 1},
    )
    response.raise_for_status()
    payload = response.json()
    return [Activity.from_api(item) for item in payload]


def parse_start_date(start_date: str | None) -> datetime | None:
    if not start_date:
        return None
    try:
        return datetime.fromisoformat(start_date.replace("Z", "+00:00"))
    except ValueError:
        return None


def new_activities(
    activities: Iterable[Activity], state: MonitorState
) -> list[Activity]:
    if state.last_activity_id is None and state.last_activity_start is None:
        return list(activities)

    last_start_dt = parse_start_date(state.last_activity_start)
    new_items = []
    for activity in activities:
        if state.last_activity_id and activity.activity_id > state.last_activity_id:
            new_items.append(activity)
            continue
        if last_start_dt:
            activity_start = parse_start_date(activity.start_date)
            if activity_start and activity_start > last_start_dt:
                new_items.append(activity)
    return new_items


def format_activity(activity: Activity) -> str:
    distance_km = activity.distance_m / 1000.0
    return (
        f"{activity.activity_id} | {activity.name} | {activity.activity_type} | "
        f"{activity.start_date} | {distance_km:.2f} km | "
        f"moving {activity.moving_time_s}s"
    )


def build_authorize_url(client_id: str, redirect_uri: str, scopes: str) -> str:
    return (
        "https://www.strava.com/oauth/authorize"
        f"?client_id={client_id}"
        "&response_type=code"
        f"&redirect_uri={redirect_uri}"
        "&approval_prompt=force"
        f"&scope={scopes}"
    )


def exchange_code_for_token(
    client: httpx.Client,
    client_id: str,
    client_secret: str,
    code: str,
) -> dict[str, Any]:
    response = client.post(
        "https://www.strava.com/oauth/token",
        data={
            "client_id": client_id,
            "client_secret": client_secret,
            "code": code,
            "grant_type": "authorization_code",
        },
    )
    response.raise_for_status()
    return response.json()


def prompt_input(prompt: str) -> str:
    return input(prompt).strip()


def extract_code(redirect_url: str) -> str | None:
    parsed = urlparse(redirect_url)
    query = parse_qs(parsed.query)
    codes = query.get("code")
    if not codes:
        return None
    return codes[0]


def run_setup(config_path: Path) -> int:
    print("Strava setup starting.")
    print("Create or view your Strava API app at:")
    print("https://www.strava.com/settings/api\n")

    client_id = prompt_input("Enter your Strava client ID: ")
    client_secret = prompt_input("Enter your Strava client secret: ")

    if not client_id or not client_secret:
        print("Client ID and client secret are required.")
        return 1

    authorize_url = build_authorize_url(
        client_id=client_id,
        redirect_uri=DEFAULT_REDIRECT_URI,
        scopes=DEFAULT_SCOPES,
    )

    print("\nOpen this URL in your browser and authorize the app:")
    print(authorize_url)
    print(
        "\nAfter approving, you will be redirected to a URL that includes "
        "a `code` parameter."
    )
    redirect_url = prompt_input("Paste the full redirect URL here: ")
    code = extract_code(redirect_url)
    if not code:
        print("Could not find a code parameter in the redirect URL.")
        return 1

    with httpx.Client(timeout=30.0) as client:
        token_payload = exchange_code_for_token(
            client=client,
            client_id=client_id,
            client_secret=client_secret,
            code=code,
        )

    config = MonitorConfig(
        client_id=client_id,
        client_secret=client_secret,
        access_token=token_payload.get("access_token"),
        refresh_token=token_payload.get("refresh_token"),
        expires_at=token_payload.get("expires_at"),
    )
    save_config(config_path, config)
    print(f"\nSaved configuration to {config_path}")
    return 0


def update_state_from_latest(state: MonitorState, activities: list[Activity]) -> None:
    if not activities:
        return
    latest = max(
        activities,
        key=lambda activity: parse_start_date(activity.start_date)
        or datetime.min.replace(tzinfo=timezone.utc),
    )
    state.last_activity_id = latest.activity_id
    state.last_activity_start = latest.start_date


def run_once(
    client: httpx.Client,
    state: MonitorState,
    per_page: int,
    log_existing: bool,
) -> bool:
    activities = fetch_activities(client, per_page)
    if not activities:
        logging.info("No activities returned from Strava.")
        return False

    if state.last_activity_id is None and state.last_activity_start is None:
        if log_existing:
            logging.info("Bootstrapping with existing activities (logging all).")
            new_items = activities
        else:
            logging.info("Bootstrapping state without logging existing activities.")
            update_state_from_latest(state, activities)
            return True
    else:
        new_items = new_activities(activities, state)

    if not new_items:
        logging.info("No new activities found.")
        return False

    for activity in sorted(new_items, key=lambda item: item.start_date):
        logging.info("New activity: %s", format_activity(activity))

    update_state_from_latest(state, activities)
    return True


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])

    if args.command == "setup":
        return run_setup(args.config_file)

    config = load_config(args.config_file)
    access_token = args.access_token or config.access_token

    if not access_token:
        logging.error(
            "Missing access token. Run `strava-monitor setup`, "
            "set STRAVA_ACCESS_TOKEN, or pass --access-token."
        )
        return 2

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s | %(levelname)s | %(message)s",
    )

    state = load_state(args.state_file)

    headers = {"Authorization": f"Bearer {access_token}"}
    with httpx.Client(headers=headers, timeout=30.0) as client:
        while True:
            changed = run_once(client, state, args.per_page, args.log_existing)
            if changed:
                save_state(args.state_file, state)

            if args.once:
                break

            time.sleep(args.poll_interval)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
